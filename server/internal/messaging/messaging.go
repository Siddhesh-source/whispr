// Package messaging relays envelopes between users over WebSocket.
//
// Invariant: payloads are opaque bytes. The server never parses, logs, or
// inspects them, and stores only the metadata needed for delivery (sender,
// recipient, conversation, timestamps, size). Envelopes are deleted as soon
// as the recipient acknowledges them, or after the retention window.
package messaging

import "github.com/go-chi/chi/v5"

type Module struct {
	gateway *Gateway
}

func New(gateway *Gateway) *Module { return &Module{gateway: gateway} }

// Routes mounts authenticated messaging endpoints.
func (m *Module) Routes(r chi.Router) {
	r.Get("/ws", m.gateway.ServeHTTP)
}
