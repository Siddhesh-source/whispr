package auth

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"whispr/server/internal/platform/httpx"
)

// JSON []byte fields are standard base64, matching java.util.Base64 on Android.

type registerRequest struct {
	IdentityKey []byte `json:"identity_key"`
	DisplayName string `json:"display_name"`
	Signature   []byte `json:"signature"`
}

type registerResponse struct {
	UserID uuid.UUID `json:"user_id"`
}

type challengeRequest struct {
	UserID uuid.UUID `json:"user_id"`
}

type challengeResponse struct {
	ChallengeID uuid.UUID `json:"challenge_id"`
	Nonce       []byte    `json:"nonce"`
	ExpiresAt   time.Time `json:"expires_at"`
}

type verifyRequest struct {
	ChallengeID uuid.UUID `json:"challenge_id"`
	Signature   []byte    `json:"signature"`
}

type verifyResponse struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
}

type meResponse struct {
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
}

type Handler struct {
	svc *Service
	log *slog.Logger
}

func NewHandler(svc *Service, log *slog.Logger) *Handler {
	return &Handler{svc: svc, log: log}
}

// PublicRoutes mounts the unauthenticated endpoints. The caller is expected
// to wrap them in a rate limiter.
func (h *Handler) PublicRoutes(r chi.Router) {
	r.Post("/register", h.register)
	r.Post("/auth/challenge", h.challenge)
	r.Post("/auth/verify", h.verify)
}

// AuthedRoutes mounts endpoints that require RequireAuth.
func (h *Handler) AuthedRoutes(r chi.Router) {
	r.Get("/me", h.me)
}

func (h *Handler) register(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if err := httpx.DecodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed request")
		return
	}
	u, created, err := h.svc.Register(r.Context(), req.IdentityKey, req.DisplayName, req.Signature)
	switch {
	case errors.Is(err, ErrInvalidInput):
		httpx.WriteError(w, http.StatusBadRequest, "invalid_input", "invalid identity key or display name")
		return
	case errors.Is(err, ErrAuthFailed):
		httpx.WriteError(w, http.StatusUnauthorized, "auth_failed", "authentication failed")
		return
	case err != nil:
		h.internal(w, "register", err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	httpx.WriteJSON(w, status, registerResponse{UserID: u.ID})
}

func (h *Handler) challenge(w http.ResponseWriter, r *http.Request) {
	var req challengeRequest
	if err := httpx.DecodeJSON(w, r, &req); err != nil || req.UserID == uuid.Nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed request")
		return
	}
	c, err := h.svc.Challenge(r.Context(), req.UserID)
	switch {
	case errors.Is(err, ErrNotFound):
		httpx.WriteError(w, http.StatusNotFound, "unknown_user", "unknown user")
		return
	case err != nil:
		h.internal(w, "challenge", err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, challengeResponse{ChallengeID: c.ID, Nonce: c.Nonce, ExpiresAt: c.ExpiresAt.UTC()})
}

func (h *Handler) verify(w http.ResponseWriter, r *http.Request) {
	var req verifyRequest
	if err := httpx.DecodeJSON(w, r, &req); err != nil || req.ChallengeID == uuid.Nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed request")
		return
	}
	token, exp, err := h.svc.Verify(r.Context(), req.ChallengeID, req.Signature)
	switch {
	case errors.Is(err, ErrAuthFailed):
		httpx.WriteError(w, http.StatusUnauthorized, "auth_failed", "authentication failed")
		return
	case err != nil:
		h.internal(w, "verify", err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, verifyResponse{Token: token, ExpiresAt: exp.UTC()})
}

func (h *Handler) me(w http.ResponseWriter, r *http.Request) {
	id, _ := UserIDFrom(r.Context())
	u, err := h.svc.User(r.Context(), id)
	if err != nil {
		h.internal(w, "me", err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, meResponse{UserID: u.ID, DisplayName: u.DisplayName})
}

func (h *Handler) internal(w http.ResponseWriter, op string, err error) {
	h.log.Error("auth handler error", "op", op, "err", err)
	httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
}

type ctxKey struct{}

// UserIDFrom returns the authenticated user ID set by RequireAuth.
func UserIDFrom(ctx context.Context) (uuid.UUID, bool) {
	id, ok := ctx.Value(ctxKey{}).(uuid.UUID)
	return id, ok
}

// RequireAuth rejects requests without a valid "Authorization: Bearer" token.
func RequireAuth(svc *Service) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			if !ok || token == "" {
				httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "missing or invalid token")
				return
			}
			id, err := svc.Authenticate(r.Context(), token)
			if err != nil {
				if !errors.Is(err, ErrUnauthorized) {
					httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
					return
				}
				httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "missing or invalid token")
				return
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
		})
	}
}
