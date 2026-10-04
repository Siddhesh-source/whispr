// Package push registers device push tokens and sends content-free wake-ups.
//
// A wake-up carries no message content, sender, or conversation: it only
// tells the app to connect and fetch. The push provider (FCM) learns that a
// device was woken and when, nothing else.
package push

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"whispr/server/internal/platform/httpx"
)

var ErrNoToken = errors.New("push: no token")

type Store interface {
	SetToken(ctx context.Context, user uuid.UUID, provider, token string, now time.Time) error
	Token(ctx context.Context, user uuid.UUID) (provider, token string, err error)
	// DeleteToken removes the user's token only if it still equals token, so a
	// stale "unregistered" response cannot remove a freshly registered token.
	DeleteToken(ctx context.Context, user uuid.UUID, token string) error
}

type PGStore struct{ pool *pgxpool.Pool }

func NewPGStore(pool *pgxpool.Pool) *PGStore { return &PGStore{pool: pool} }

func (s *PGStore) SetToken(ctx context.Context, user uuid.UUID, provider, token string, now time.Time) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO push_tokens (user_id, provider, token, updated_at) VALUES ($1, $2, $3, $4)
		ON CONFLICT (user_id) DO UPDATE SET provider = EXCLUDED.provider, token = EXCLUDED.token, updated_at = EXCLUDED.updated_at`,
		user, provider, token, now)
	return err
}

func (s *PGStore) Token(ctx context.Context, user uuid.UUID) (string, string, error) {
	var provider, token string
	err := s.pool.QueryRow(ctx, `SELECT provider, token FROM push_tokens WHERE user_id = $1`, user).Scan(&provider, &token)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", "", ErrNoToken
	}
	return provider, token, err
}

func (s *PGStore) DeleteToken(ctx context.Context, user uuid.UUID, token string) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM push_tokens WHERE user_id = $1 AND token = $2`, user, token)
	return err
}

type Module struct {
	store  Store
	log    *slog.Logger
	userID func(context.Context) (uuid.UUID, bool)
}

func NewModule(store Store, log *slog.Logger, userID func(context.Context) (uuid.UUID, bool)) *Module {
	return &Module{store: store, log: log, userID: userID}
}

type tokenRequest struct {
	Provider string `json:"provider"`
	Token    string `json:"token"`
}

// Routes mounts authenticated push endpoints.
func (m *Module) Routes(r chi.Router) {
	r.Put("/push/token", m.putToken)
	r.Delete("/push/token", m.deleteToken)
}

func (m *Module) putToken(w http.ResponseWriter, r *http.Request) {
	user, _ := m.userID(r.Context())
	var req tokenRequest
	if err := httpx.DecodeJSON(w, r, &req); err != nil || req.Provider != "fcm" || req.Token == "" || len(req.Token) > 4096 {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed request")
		return
	}
	if err := m.store.SetToken(r.Context(), user, req.Provider, req.Token, time.Now().UTC()); err != nil {
		m.log.Error("push token store failed", "err", err)
		httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (m *Module) deleteToken(w http.ResponseWriter, r *http.Request) {
	user, _ := m.userID(r.Context())
	_, token, err := m.store.Token(r.Context(), user)
	if err == nil {
		err = m.store.DeleteToken(r.Context(), user, token)
	}
	if err != nil && !errors.Is(err, ErrNoToken) {
		m.log.Error("push token delete failed", "err", err)
		httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
