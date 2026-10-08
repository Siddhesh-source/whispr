package messaging

import (
	"sync"

	"github.com/google/uuid"
)

// Hub tracks which users have a live connection on this server instance.
// It holds no messages: everything durable is in the Store, so a restart
// loses only who is online (clients reconnect and drain their queue).
//
// Single instance only. Running several instances needs a cross-instance
// wake-up (e.g. Postgres LISTEN/NOTIFY) in place of Notify.
type Hub struct {
	mu    sync.Mutex
	conns map[uuid.UUID]*session
}

func NewHub() *Hub { return &Hub{conns: map[uuid.UUID]*session{}} }

// session is the hub's handle on one live connection.
type session struct {
	// wake is signalled when new envelopes may be pending (capacity 1, so
	// signals coalesce; the writer always re-reads the store).
	wake chan struct{}
	// transient carries frames that are never stored (typing indicators).
	transient chan []byte
	// kick closes the connection when a newer one replaces it.
	kick func()
}

func newSession(kick func()) *session {
	return &session{wake: make(chan struct{}, 1), transient: make(chan []byte, 32), kick: kick}
}

// register makes s the user's live session, closing any previous one
// (one device per account).
func (h *Hub) register(user uuid.UUID, s *session) {
	h.mu.Lock()
	old := h.conns[user]
	h.conns[user] = s
	h.mu.Unlock()
	if old != nil {
		old.kick()
	}
	s.signal()
}

// Disconnect closes the user's live connection, if any (after a logout or
// account deletion). A reconnect must authenticate again.
func (h *Hub) Disconnect(user uuid.UUID) {
	h.mu.Lock()
	s := h.conns[user]
	delete(h.conns, user)
	h.mu.Unlock()
	if s != nil {
		s.kick()
	}
}

func (h *Hub) unregister(user uuid.UUID, s *session) {
	h.mu.Lock()
	if h.conns[user] == s {
		delete(h.conns, user)
	}
	h.mu.Unlock()
}

// Notify wakes the user's connection, if any, and reports whether one exists.
func (h *Hub) Notify(user uuid.UUID) bool {
	h.mu.Lock()
	s := h.conns[user]
	h.mu.Unlock()
	if s == nil {
		return false
	}
	s.signal()
	return true
}

// sendTransient delivers a frame to a connected user, dropping it if the user
// is offline or their buffer is full. Transient frames are best effort.
func (h *Hub) sendTransient(user uuid.UUID, frame []byte) bool {
	h.mu.Lock()
	s := h.conns[user]
	h.mu.Unlock()
	if s == nil {
		return false
	}
	select {
	case s.transient <- frame:
		return true
	default:
		return false
	}
}

func (s *session) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}
