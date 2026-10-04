// Package profile manages the public parts of an account: display name and
// an optional username for lookup. Profile pictures are never uploaded.
package profile

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"net/http"
	"regexp"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"whispr/server/internal/auth"
	"whispr/server/internal/platform/httpx"
)

var (
	ErrNotFound      = errors.New("profile: not found")
	ErrInvalid       = errors.New("profile: invalid input")
	ErrNoneAvailable = errors.New("profile: no username available for this nickname")
)

var (
	nicknameRe = regexp.MustCompile(`^[a-z][a-z0-9_]{2,31}$`)
	usernameRe = regexp.MustCompile(`^[a-z][a-z0-9_]{2,31}\.[0-9]{2,3}$`)
)

type Profile struct {
	UserID      uuid.UUID
	DisplayName string
	IdentityKey []byte
	Username    *string
}

type Store struct{ pool *pgxpool.Pool }

func NewStore(pool *pgxpool.Pool) *Store { return &Store{pool: pool} }

func (s *Store) Get(ctx context.Context, id uuid.UUID) (Profile, error) {
	return s.scan(s.pool.QueryRow(ctx, `SELECT id, display_name, identity_key, username FROM users WHERE id = $1`, id))
}

func (s *Store) ByUsername(ctx context.Context, username string) (Profile, error) {
	return s.scan(s.pool.QueryRow(ctx, `SELECT id, display_name, identity_key, username FROM users WHERE username = $1`, username))
}

func (s *Store) scan(row pgx.Row) (Profile, error) {
	var p Profile
	err := row.Scan(&p.UserID, &p.DisplayName, &p.IdentityKey, &p.Username)
	if errors.Is(err, pgx.ErrNoRows) {
		return Profile{}, ErrNotFound
	}
	return p, err
}

func (s *Store) SetDisplayName(ctx context.Context, id uuid.UUID, name string) error {
	_, err := s.pool.Exec(ctx, `UPDATE users SET display_name = $2 WHERE id = $1`, id, name)
	return err
}

// ClaimUsername gives the user nickname plus a random free number. Random
// rather than sequential numbers avoid leaking how many people share a nickname.
func (s *Store) ClaimUsername(ctx context.Context, id uuid.UUID, nickname string) (string, error) {
	for _, digits := range []int{2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3} {
		candidate := fmt.Sprintf("%s.%0*d", nickname, digits, randomNumber(digits))
		_, err := s.pool.Exec(ctx, `UPDATE users SET username = $2 WHERE id = $1`, id, candidate)
		if err == nil {
			return candidate, nil
		}
		var pgErr *pgconn.PgError
		if !errors.As(err, &pgErr) || pgErr.Code != "23505" {
			return "", err
		}
	}
	return "", ErrNoneAvailable
}

func (s *Store) ClearUsername(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `UPDATE users SET username = NULL WHERE id = $1`, id)
	return err
}

// randomNumber returns a uniformly random number with exactly `digits`
// digits, excluding all-zero (01–99 or 100–999).
func randomNumber(digits int) int {
	lo, hi := 1, 99
	if digits == 3 {
		lo, hi = 100, 999
	}
	n, _ := rand.Int(rand.Reader, big.NewInt(int64(hi-lo+1)))
	return lo + int(n.Int64())
}

// NormalizeUsername lowercases and validates a full handle ("Sam.42" → "sam.42").
func NormalizeUsername(s string) (string, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	return s, usernameRe.MatchString(s)
}

type Module struct {
	store         *Store
	log           *slog.Logger
	userID        func(context.Context) (uuid.UUID, bool)
	lookupLimiter func(http.Handler) http.Handler
}

func NewModule(store *Store, log *slog.Logger, userID func(context.Context) (uuid.UUID, bool), lookupLimiter func(http.Handler) http.Handler) *Module {
	return &Module{store: store, log: log, userID: userID, lookupLimiter: lookupLimiter}
}

type profileResponse struct {
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
	IdentityKey []byte    `json:"identity_key"`
	Username    *string   `json:"username,omitempty"`
}

// Routes mounts authenticated profile endpoints.
func (m *Module) Routes(r chi.Router) {
	r.Get("/me/profile", m.getMine)
	r.Put("/me/profile", m.putProfile)
	r.Put("/me/username", m.putUsername)
	r.Delete("/me/username", m.deleteUsername)
	// Separate, stricter limit: lookups are the enumeration surface.
	r.With(m.lookupLimiter).Get("/usernames/{username}", m.lookup)
}

func (m *Module) getMine(w http.ResponseWriter, r *http.Request) {
	id, _ := m.userID(r.Context())
	p, err := m.store.Get(r.Context(), id)
	if err != nil {
		m.internal(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, toResponse(p))
}

func (m *Module) putProfile(w http.ResponseWriter, r *http.Request) {
	id, _ := m.userID(r.Context())
	var req struct {
		DisplayName string `json:"display_name"`
	}
	if err := httpx.DecodeJSON(w, r, &req); err != nil || auth.ValidateDisplayName(req.DisplayName) != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_input", "invalid display name")
		return
	}
	if err := m.store.SetDisplayName(r.Context(), id, req.DisplayName); err != nil {
		m.internal(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (m *Module) putUsername(w http.ResponseWriter, r *http.Request) {
	id, _ := m.userID(r.Context())
	var req struct {
		Nickname string `json:"nickname"`
	}
	if err := httpx.DecodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed request")
		return
	}
	nick := strings.ToLower(strings.TrimSpace(req.Nickname))
	if !nicknameRe.MatchString(nick) {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_input", "nickname must be 3-32 letters, digits or _, starting with a letter")
		return
	}
	username, err := m.store.ClaimUsername(r.Context(), id, nick)
	if errors.Is(err, ErrNoneAvailable) {
		httpx.WriteError(w, http.StatusConflict, "unavailable", "try a different nickname")
		return
	}
	if err != nil {
		m.internal(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"username": username})
}

func (m *Module) deleteUsername(w http.ResponseWriter, r *http.Request) {
	id, _ := m.userID(r.Context())
	if err := m.store.ClearUsername(r.Context(), id); err != nil {
		m.internal(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (m *Module) lookup(w http.ResponseWriter, r *http.Request) {
	username, ok := NormalizeUsername(chi.URLParam(r, "username"))
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_input", "malformed username")
		return
	}
	p, err := m.store.ByUsername(r.Context(), username)
	if errors.Is(err, ErrNotFound) {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "no such username")
		return
	}
	if err != nil {
		m.internal(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, toResponse(p))
}

func toResponse(p Profile) profileResponse {
	return profileResponse{UserID: p.UserID, DisplayName: p.DisplayName, IdentityKey: p.IdentityKey, Username: p.Username}
}

func (m *Module) internal(w http.ResponseWriter, err error) {
	m.log.Error("profile handler error", "err", err)
	httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
}
