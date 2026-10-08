package auth

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"

	"whispr/server/internal/sigverify/sigverifytest"
)

func TestMemStoreContract(t *testing.T) {
	storeContract(t, func(t *testing.T) Store { return newMemStore() })
}

// storeContract checks behaviour every Store implementation must share.
func storeContract(t *testing.T, newStore func(t *testing.T) Store) {
	ctx := context.Background()
	now := time.Now().UTC().Truncate(time.Microsecond)

	t.Run("CreateUserIsIdempotentPerIdentityKey", func(t *testing.T) {
		s := newStore(t)
		key := sigverifytest.NewPublicKey()
		first, created, err := s.CreateUser(ctx, User{ID: uuid.New(), IdentityKey: key, DisplayName: "Ada"})
		if err != nil || !created {
			t.Fatalf("first create: created=%v err=%v", created, err)
		}
		again, created, err := s.CreateUser(ctx, User{ID: uuid.New(), IdentityKey: key, DisplayName: "Other"})
		if err != nil || created {
			t.Fatalf("second create: created=%v err=%v", created, err)
		}
		if again.ID != first.ID || again.DisplayName != "Ada" {
			t.Fatalf("got %+v, want existing %+v", again, first)
		}
	})

	t.Run("GetUnknownUser", func(t *testing.T) {
		if _, err := newStore(t).GetUser(ctx, uuid.New()); !errors.Is(err, ErrNotFound) {
			t.Fatalf("err = %v", err)
		}
	})

	t.Run("ChallengeIsSingleUse", func(t *testing.T) {
		s := newStore(t)
		u := mustUser(t, s)
		c := Challenge{ID: uuid.New(), UserID: u.ID, Nonce: make([]byte, NonceLen), ExpiresAt: now.Add(time.Minute)}
		if err := s.CreateChallenge(ctx, c); err != nil {
			t.Fatal(err)
		}
		got, err := s.ConsumeChallenge(ctx, c.ID, now)
		if err != nil || got.UserID != u.ID {
			t.Fatalf("consume: %+v %v", got, err)
		}
		if _, err := s.ConsumeChallenge(ctx, c.ID, now); !errors.Is(err, ErrNotFound) {
			t.Fatalf("second consume err = %v", err)
		}
	})

	t.Run("ChallengeConsumedOnceUnderConcurrency", func(t *testing.T) {
		s := newStore(t)
		u := mustUser(t, s)
		c := Challenge{ID: uuid.New(), UserID: u.ID, Nonce: make([]byte, NonceLen), ExpiresAt: now.Add(time.Minute)}
		if err := s.CreateChallenge(ctx, c); err != nil {
			t.Fatal(err)
		}
		var wins atomic.Int32
		var wg sync.WaitGroup
		for range 16 {
			wg.Go(func() {
				if _, err := s.ConsumeChallenge(ctx, c.ID, now); err == nil {
					wins.Add(1)
				}
			})
		}
		wg.Wait()
		if wins.Load() != 1 {
			t.Fatalf("challenge consumed %d times", wins.Load())
		}
	})

	t.Run("ExpiredChallengeRejected", func(t *testing.T) {
		s := newStore(t)
		u := mustUser(t, s)
		c := Challenge{ID: uuid.New(), UserID: u.ID, Nonce: make([]byte, NonceLen), ExpiresAt: now}
		if err := s.CreateChallenge(ctx, c); err != nil {
			t.Fatal(err)
		}
		if _, err := s.ConsumeChallenge(ctx, c.ID, now); !errors.Is(err, ErrNotFound) {
			t.Fatalf("err = %v", err)
		}
	})

	t.Run("TokenLookupRespectsExpiry", func(t *testing.T) {
		s := newStore(t)
		u := mustUser(t, s)
		hash := hashToken([]byte("t"))
		if err := s.CreateToken(ctx, hash, u.ID, now.Add(time.Minute)); err != nil {
			t.Fatal(err)
		}
		if id, exp, err := s.LookupToken(ctx, hash, now); err != nil || id != u.ID || !exp.Equal(now.Add(time.Minute)) {
			t.Fatalf("lookup: %v %v %v", id, exp, err)
		}
		if _, _, err := s.LookupToken(ctx, hash, now.Add(time.Minute)); !errors.Is(err, ErrNotFound) {
			t.Fatalf("expired lookup err = %v", err)
		}
		if err := s.DeleteExpired(ctx, now.Add(2*time.Minute)); err != nil {
			t.Fatal(err)
		}
		if _, _, err := s.LookupToken(ctx, hash, now); !errors.Is(err, ErrNotFound) {
			t.Fatalf("token survived DeleteExpired: %v", err)
		}
	})

	t.Run("DeleteTokenRevokesOnlyThatToken", func(t *testing.T) {
		s := newStore(t)
		u := mustUser(t, s)
		a, b := hashToken([]byte("a")), hashToken([]byte("b"))
		for _, h := range [][]byte{a, b} {
			if err := s.CreateToken(ctx, h, u.ID, now.Add(time.Minute)); err != nil {
				t.Fatal(err)
			}
		}
		if err := s.DeleteToken(ctx, a); err != nil {
			t.Fatal(err)
		}
		if _, _, err := s.LookupToken(ctx, a, now); !errors.Is(err, ErrNotFound) {
			t.Fatalf("revoked token still valid: %v", err)
		}
		if _, _, err := s.LookupToken(ctx, b, now); err != nil {
			t.Fatalf("other token revoked: %v", err)
		}
		if err := s.DeleteToken(ctx, a); err != nil {
			t.Fatalf("second delete: %v", err)
		}
	})

	t.Run("DeleteUserRemovesUserAndTokens", func(t *testing.T) {
		s := newStore(t)
		u := mustUser(t, s)
		h := hashToken([]byte("t"))
		if err := s.CreateToken(ctx, h, u.ID, now.Add(time.Minute)); err != nil {
			t.Fatal(err)
		}
		if err := s.DeleteUser(ctx, u.ID); err != nil {
			t.Fatal(err)
		}
		if _, err := s.GetUser(ctx, u.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("user survived: %v", err)
		}
		if _, _, err := s.LookupToken(ctx, h, now); !errors.Is(err, ErrNotFound) {
			t.Fatalf("token survived account deletion: %v", err)
		}
		if err := s.DeleteUser(ctx, u.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("second delete err = %v", err)
		}
	})
}

func mustUser(t *testing.T, s Store) User {
	t.Helper()
	u, _, err := s.CreateUser(context.Background(), User{ID: uuid.New(), IdentityKey: sigverifytest.NewPublicKey(), DisplayName: "Test"})
	if err != nil {
		t.Fatal(err)
	}
	return u
}
