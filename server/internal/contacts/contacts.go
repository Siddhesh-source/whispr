// Package contacts will support QR-based contact adding (fetching a user's
// public identity material by user ID). There is no contact discovery by
// phone number or address book, and the server does not store contact lists.
package contacts

import "github.com/go-chi/chi/v5"

type Module struct{}

func New() *Module { return &Module{} }

// Routes mounts authenticated contacts endpoints. None exist in Phase 1.
func (m *Module) Routes(chi.Router) {}
