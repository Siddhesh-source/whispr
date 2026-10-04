// Package messaging will relay end-to-end encrypted envelopes between users.
//
// Invariant for everything added here: message bodies are opaque bytes. The
// server never parses, logs, or inspects them, and stores only the metadata
// needed for delivery (recipient, size, timestamps), deleting envelopes once
// delivered.
package messaging

import "github.com/go-chi/chi/v5"

type Module struct{}

func New() *Module { return &Module{} }

// Routes mounts authenticated messaging endpoints. None exist in Phase 1.
func (m *Module) Routes(chi.Router) {}
