package auth

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
)

// ErrNotFound is returned by Store lookups that match nothing (including
// expired or already-consumed rows).
var ErrNotFound = errors.New("auth: not found")

type User struct {
	ID          uuid.UUID
	IdentityKey []byte
	DisplayName string
	CreatedAt   time.Time
}

type Challenge struct {
	ID        uuid.UUID
	UserID    uuid.UUID
	Nonce     []byte
	ExpiresAt time.Time
}

type Store interface {
	// CreateUser inserts u unless its identity key is already registered, in
	// which case it returns the existing user and created=false.
	CreateUser(ctx context.Context, u User) (user User, created bool, err error)
	GetUser(ctx context.Context, id uuid.UUID) (User, error)

	CreateChallenge(ctx context.Context, c Challenge) error
	// ConsumeChallenge atomically marks an unused, unexpired challenge as used
	// and returns it. Any other case returns ErrNotFound.
	ConsumeChallenge(ctx context.Context, id uuid.UUID, now time.Time) (Challenge, error)

	CreateToken(ctx context.Context, tokenHash []byte, userID uuid.UUID, expiresAt time.Time) error
	// LookupToken returns the owner of an unexpired token hash.
	LookupToken(ctx context.Context, tokenHash []byte, now time.Time) (uuid.UUID, error)

	// DeleteExpired removes expired challenges and tokens.
	DeleteExpired(ctx context.Context, now time.Time) error
}
