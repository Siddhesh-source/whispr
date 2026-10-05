// Package attachments stores encrypted media blobs.
//
// Invariant: the server never sees attachment keys or plaintext. Clients
// encrypt each file with a fresh random key (libsignal AES-256-GCM), upload
// only the ciphertext here, and send the key and digest inside an
// end-to-end encrypted message. The server keeps the blob for the retention
// window and records only the uploader, size and upload time.
package attachments

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"whispr/server/internal/platform/httpx"
)

const (
	// MaxPlaintextBytes is the largest file a client may send.
	MaxPlaintextBytes = 25 << 20
	// MaxBlobBytes adds the 12-byte nonce and 16-byte GCM tag.
	MaxBlobBytes = MaxPlaintextBytes + 28
	// transferTimeout replaces the server-wide 15 s read/write timeouts for
	// uploads and downloads, which would otherwise cut off any transfer
	// slower than about 1.7 MB/s.
	transferTimeout = 5 * time.Minute
)

// Store tracks blobs for retention.
type Store interface {
	Insert(ctx context.Context, id, uploader uuid.UUID, size int64, now time.Time) error
	// Exists reports whether id is recorded and newer than notBefore.
	Exists(ctx context.Context, id uuid.UUID, notBefore time.Time) (bool, error)
	// Expired lists up to limit IDs created before cutoff.
	Expired(ctx context.Context, cutoff time.Time, limit int) ([]uuid.UUID, error)
	Delete(ctx context.Context, id uuid.UUID) error
}

type PGStore struct{ pool *pgxpool.Pool }

func NewPGStore(pool *pgxpool.Pool) *PGStore { return &PGStore{pool: pool} }

func (s *PGStore) Insert(ctx context.Context, id, uploader uuid.UUID, size int64, now time.Time) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO attachments (id, uploader_id, size, created_at) VALUES ($1, $2, $3, $4)`,
		id, uploader, size, now)
	return err
}

func (s *PGStore) Exists(ctx context.Context, id uuid.UUID, notBefore time.Time) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM attachments WHERE id = $1 AND created_at >= $2)`, id, notBefore).Scan(&ok)
	return ok, err
}

func (s *PGStore) Expired(ctx context.Context, cutoff time.Time, limit int) ([]uuid.UUID, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id FROM attachments WHERE created_at < $1 ORDER BY created_at LIMIT $2`, cutoff, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

func (s *PGStore) Delete(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM attachments WHERE id = $1`, id)
	return err
}

type Options struct {
	// Retention is how long a blob is kept after upload (default 30 days).
	Retention time.Duration
	// Uploads limits uploads per user (default 30 a minute).
	Uploads *httpx.KeyedLimiter
	Now     func() time.Time
}

type Module struct {
	store   Store
	objects Objects
	log     *slog.Logger
	userID  func(context.Context) (uuid.UUID, bool)
	opts    Options
}

func NewModule(store Store, objects Objects, log *slog.Logger, userID func(context.Context) (uuid.UUID, bool), opts Options) *Module {
	if opts.Retention == 0 {
		opts.Retention = 30 * 24 * time.Hour
	}
	if opts.Uploads == nil {
		opts.Uploads = httpx.NewKeyedLimiter(30, time.Minute)
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	return &Module{store: store, objects: objects, log: log, userID: userID, opts: opts}
}

// Routes mounts authenticated attachment endpoints.
func (m *Module) Routes(r chi.Router) {
	r.Post("/attachments", m.upload)
	r.Get("/attachments/{id}", m.download)
}

func objectKey(id uuid.UUID) string { return "attachments/" + id.String() }

type uploadResponse struct {
	ID uuid.UUID `json:"id"`
}

func (m *Module) upload(w http.ResponseWriter, r *http.Request) {
	user, _ := m.userID(r.Context())
	size := r.ContentLength
	switch {
	case size < 0:
		httpx.WriteError(w, http.StatusLengthRequired, "length_required", "Content-Length is required")
		return
	case size == 0:
		httpx.WriteError(w, http.StatusBadRequest, "empty", "empty attachment")
		return
	case size > MaxBlobBytes:
		httpx.WriteError(w, http.StatusRequestEntityTooLarge, "too_large", "attachment too large")
		return
	}
	if !m.opts.Uploads.Allow(user.String()) {
		httpx.WriteRateLimited(w, time.Minute)
		return
	}
	extendDeadlines(w)
	id := uuid.New()
	body := http.MaxBytesReader(w, r.Body, size)
	if err := m.objects.Put(r.Context(), objectKey(id), body, size); err != nil {
		m.internal(w, fmt.Errorf("put object: %w", err))
		return
	}
	if err := m.store.Insert(r.Context(), id, user, size, m.opts.Now().UTC()); err != nil {
		// Don't leave an object the janitor can't find.
		if derr := m.objects.Delete(context.WithoutCancel(r.Context()), objectKey(id)); derr != nil {
			m.log.Error("attachment cleanup failed", "err", derr)
		}
		m.internal(w, fmt.Errorf("insert attachment: %w", err))
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, uploadResponse{ID: id})
}

func (m *Module) download(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed attachment id")
		return
	}
	ok, err := m.store.Exists(r.Context(), id, m.opts.Now().Add(-m.opts.Retention))
	if err != nil {
		m.internal(w, err)
		return
	}
	if !ok {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "attachment not found or expired")
		return
	}
	obj, size, err := m.objects.Get(r.Context(), objectKey(id))
	if errors.Is(err, ErrNotFound) {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "attachment not found or expired")
		return
	}
	if err != nil {
		m.internal(w, err)
		return
	}
	defer func() { _ = obj.Close() }()
	extendDeadlines(w)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	if _, err := io.Copy(w, obj); err != nil {
		// Headers are gone; the client sees a short body and its digest check fails.
		m.log.Warn("attachment download interrupted", "err", err)
	}
}

func extendDeadlines(w http.ResponseWriter) {
	rc := http.NewResponseController(w)
	deadline := time.Now().Add(transferTimeout)
	_ = rc.SetReadDeadline(deadline)
	_ = rc.SetWriteDeadline(deadline)
}

func (m *Module) internal(w http.ResponseWriter, err error) {
	m.log.Error("attachments handler error", "err", err)
	httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
}

// Purge deletes blobs older than the retention window: the object first,
// then its row, so a failure leaves the row for the next run to retry.
func (m *Module) Purge(ctx context.Context) (int, error) {
	cutoff := m.opts.Now().Add(-m.opts.Retention)
	deleted := 0
	for {
		ids, err := m.store.Expired(ctx, cutoff, 500)
		if err != nil {
			return deleted, err
		}
		for _, id := range ids {
			if err := m.objects.Delete(ctx, objectKey(id)); err != nil {
				return deleted, err
			}
			if err := m.store.Delete(ctx, id); err != nil {
				return deleted, err
			}
			deleted++
		}
		if len(ids) < 500 {
			return deleted, nil
		}
	}
}

// RunJanitor calls Purge every interval until ctx is cancelled.
func (m *Module) RunJanitor(ctx context.Context, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if n, err := m.Purge(ctx); err != nil && ctx.Err() == nil {
				m.log.Error("attachments janitor failed", "err", err, "deleted", n)
			}
		}
	}
}
