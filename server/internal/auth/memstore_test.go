package auth

import (
	"bytes"
	"context"
	"sync"
	"time"

	"github.com/google/uuid"
)

// memStore is an in-memory Store for unit tests. storeContract runs the same
// assertions against it and PGStore so the two cannot drift.
type memStore struct {
	mu         sync.Mutex
	users      map[uuid.UUID]User
	challenges map[uuid.UUID]*memChallenge
	tokens     map[string]memToken
	now        func() time.Time
}

type memChallenge struct {
	Challenge
	used bool
}

type memToken struct {
	userID    uuid.UUID
	expiresAt time.Time
}

func newMemStore() *memStore {
	return &memStore{
		users:      map[uuid.UUID]User{},
		challenges: map[uuid.UUID]*memChallenge{},
		tokens:     map[string]memToken{},
		now:        time.Now,
	}
}

func (m *memStore) CreateUser(_ context.Context, u User) (User, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, existing := range m.users {
		if bytes.Equal(existing.IdentityKey, u.IdentityKey) {
			return existing, false, nil
		}
	}
	u.CreatedAt = m.now()
	m.users[u.ID] = u
	return u, true, nil
}

func (m *memStore) GetUser(_ context.Context, id uuid.UUID) (User, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	u, ok := m.users[id]
	if !ok {
		return User{}, ErrNotFound
	}
	return u, nil
}

func (m *memStore) CreateChallenge(_ context.Context, c Challenge) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.challenges[c.ID] = &memChallenge{Challenge: c}
	return nil
}

func (m *memStore) ConsumeChallenge(_ context.Context, id uuid.UUID, now time.Time) (Challenge, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.challenges[id]
	if !ok || c.used || !c.ExpiresAt.After(now) {
		return Challenge{}, ErrNotFound
	}
	c.used = true
	return c.Challenge, nil
}

func (m *memStore) CreateToken(_ context.Context, hash []byte, userID uuid.UUID, exp time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.tokens[string(hash)] = memToken{userID: userID, expiresAt: exp}
	return nil
}

func (m *memStore) LookupToken(_ context.Context, hash []byte, now time.Time) (uuid.UUID, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, ok := m.tokens[string(hash)]
	if !ok || !t.expiresAt.After(now) {
		return uuid.Nil, ErrNotFound
	}
	return t.userID, nil
}

func (m *memStore) DeleteExpired(_ context.Context, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, c := range m.challenges {
		if !c.ExpiresAt.After(now) {
			delete(m.challenges, id)
		}
	}
	for h, t := range m.tokens {
		if !t.expiresAt.After(now) {
			delete(m.tokens, h)
		}
	}
	return nil
}
