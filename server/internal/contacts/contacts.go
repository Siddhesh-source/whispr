// Package contacts serves public identity material for adding a contact.
//
// Phase 2 adds contacts by pasting a user ID; Phase 3 replaces this with QR
// codes that carry the identity key for out-of-band verification. There is no
// discovery by phone number or address book, and the server stores no
// contact lists.
package contacts

import (
	"context"
	"errors"
	"log/slog"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"whispr/server/internal/auth"
	"whispr/server/internal/platform/httpx"
)

// UserLookup is satisfied by auth.Store.
type UserLookup interface {
	GetUser(ctx context.Context, id uuid.UUID) (auth.User, error)
}

type Module struct {
	users UserLookup
	log   *slog.Logger
}

func New(users UserLookup, log *slog.Logger) *Module { return &Module{users: users, log: log} }

type userResponse struct {
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
	IdentityKey []byte    `json:"identity_key"`
}

// Routes mounts authenticated contacts endpoints.
func (m *Module) Routes(r chi.Router) {
	r.Get("/users/{id}", m.getUser)
}

func (m *Module) getUser(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed user id")
		return
	}
	u, err := m.users.GetUser(r.Context(), id)
	if errors.Is(err, auth.ErrNotFound) {
		httpx.WriteError(w, http.StatusNotFound, "unknown_user", "unknown user")
		return
	}
	if err != nil {
		m.log.Error("contacts lookup failed", "err", err)
		httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, userResponse{UserID: u.ID, DisplayName: u.DisplayName, IdentityKey: u.IdentityKey})
}
