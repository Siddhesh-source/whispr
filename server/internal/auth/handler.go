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

type deleteAccountRequest struct {
	ChallengeID uuid.UUID `json:"challenge_id"`
	Signature   []byte    `json:"signature"`
}

type Handler struct {
	svc       *Service
	log       *slog.Logger
	onSignOut func(uuid.UUID)
}

// NewHandler serves the auth endpoints. onSignOut (may be nil) runs after a
// logout or account deletion, to close the user's live connections.
func NewHandler(svc *Service, log *slog.Logger, onSignOut func(uuid.UUID)) *Handler {
	if onSignOut == nil {
		onSignOut = func(uuid.UUID) {}
	}
	return &Handler{svc: svc, log: log, onSignOut: onSignOut}
}

// PublicRoutes mounts the unauthenticated endpoints. The caller is expected
// to wrap them in a rate limiter; registerLimit (may be nil) is an extra,
// stricter limit on account creation.
func (h *Handler) PublicRoutes(r chi.Router, registerLimit func(http.Handler) http.Handler) {
	if registerLimit != nil {
		r.With(registerLimit).Post("/register", h.register)
	} else {
		r.Post("/register", h.register)
	}
	r.Post("/auth/challenge", h.challenge)
	r.Post("/auth/verify", h.verify)
}

// AuthedRoutes mounts endpoints that require RequireAuth.
func (h *Handler) AuthedRoutes(r chi.Router) {
	r.Get("/me", h.me)
	r.Delete("/me", h.deleteAccount)
	r.Post("/auth/logout", h.logout)
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

func (h *Handler) logout(w http.ResponseWriter, r *http.Request) {
	id, _ := UserIDFrom(r.Context())
	token, _ := bearer(r)
	switch err := h.svc.Logout(r.Context(), token); {
	case errors.Is(err, ErrUnauthorized):
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "missing or invalid token")
		return
	case err != nil:
		h.internal(w, "logout", err)
		return
	}
	h.onSignOut(id)
	w.WriteHeader(http.StatusNoContent)
}

// deleteAccount needs the bearer token and a fresh challenge signed with
// DeleteMessage (see Service.DeleteAccount).
func (h *Handler) deleteAccount(w http.ResponseWriter, r *http.Request) {
	id, _ := UserIDFrom(r.Context())
	var req deleteAccountRequest
	if err := httpx.DecodeJSON(w, r, &req); err != nil || req.ChallengeID == uuid.Nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed request")
		return
	}
	switch err := h.svc.DeleteAccount(r.Context(), id, req.ChallengeID, req.Signature); {
	case errors.Is(err, ErrAuthFailed):
		httpx.WriteError(w, http.StatusUnauthorized, "auth_failed", "authentication failed")
		return
	case err != nil:
		h.internal(w, "delete account", err)
		return
	}
	h.onSignOut(id)
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) internal(w http.ResponseWriter, op string, err error) {
	h.log.Error("auth handler error", "op", op, "err", err)
	httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
}

type ctxKey struct{}

type session struct {
	id        uuid.UUID
	expiresAt time.Time
}

// UserIDFrom returns the authenticated user ID set by RequireAuth.
func UserIDFrom(ctx context.Context) (uuid.UUID, bool) {
	s, ok := ctx.Value(ctxKey{}).(session)
	return s.id, ok
}

// TokenExpiryFrom returns when the request's bearer token expires. Long-lived
// connections must end then, or a revoked or expired token would live on.
func TokenExpiryFrom(ctx context.Context) (time.Time, bool) {
	s, ok := ctx.Value(ctxKey{}).(session)
	return s.expiresAt, ok
}

func bearer(r *http.Request) (string, bool) {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	return token, ok && token != ""
}

// RequireAuth rejects requests without a valid "Authorization: Bearer" token.
func RequireAuth(svc *Service) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := bearer(r)
			if !ok {
				httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "missing or invalid token")
				return
			}
			id, exp, err := svc.Authenticate(r.Context(), token)
			if err != nil {
				if !errors.Is(err, ErrUnauthorized) {
					httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
					return
				}
				httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "missing or invalid token")
				return
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, session{id: id, expiresAt: exp})))
		})
	}
}
