package auth

import (
	"context"
	"encoding/hex"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"whispr/server/internal/sigverify/sigverifytest"
)

// Shared vector with android/data/.../AuthMessagesTest.kt.
const deleteVector = "7768697370722d64656c6574652d76310000112233445566778899aabbccddeeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

func TestDeleteMessageVector(t *testing.T) {
	nonce := make([]byte, NonceLen)
	for i := range nonce {
		nonce[i] = byte(i)
	}
	id := uuid.MustParse("00112233-4455-6677-8899-aabbccddeeff")
	if got := hex.EncodeToString(DeleteMessage(id, nonce)); got != deleteVector {
		t.Errorf("DeleteMessage = %s", got)
	}
}

func TestAuthenticateReturnsTokenExpiry(t *testing.T) {
	svc, clock := newTestService(t)
	u, key := register(t, svc, "Ada")
	tok := login(t, svc, u, key)
	_, exp, err := svc.Authenticate(context.Background(), tok)
	if err != nil || !exp.Equal(clock.t.Add(15*time.Minute)) {
		t.Fatalf("exp = %v, err = %v", exp, err)
	}
}

func TestLogoutRevokesOnlyThatToken(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	u, key := register(t, svc, "Ada")
	a, b := login(t, svc, u, key), login(t, svc, u, key)
	if err := svc.Logout(ctx, a); err != nil {
		t.Fatal(err)
	}
	if _, _, err := svc.Authenticate(ctx, a); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("revoked token still works: %v", err)
	}
	if _, _, err := svc.Authenticate(ctx, b); err != nil {
		t.Fatalf("other token revoked: %v", err)
	}
	if err := svc.Logout(ctx, a); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("second logout err = %v", err)
	}
	if err := svc.Logout(ctx, "garbage"); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("malformed logout err = %v", err)
	}
}

func deleteChallenge(t *testing.T, svc *Service, id uuid.UUID) Challenge {
	t.Helper()
	c, err := svc.Challenge(context.Background(), id)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestDeleteAccountNeedsAFreshDeleteSignature(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	u, key := register(t, svc, "Ada")
	other, otherKey := register(t, svc, "Bob")
	_ = login(t, svc, u, key) // an active session must not block deletion

	// A sign-in signature over the same nonce is not a delete signature.
	c := deleteChallenge(t, svc, u.ID)
	if err := svc.DeleteAccount(ctx, u.ID, c.ID, sigverifytest.Sign(key, AuthMessage(u.ID, c.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("auth signature deleted the account: %v", err)
	}
	// The failed attempt burned that challenge.
	if err := svc.DeleteAccount(ctx, u.ID, c.ID, sigverifytest.Sign(key, DeleteMessage(u.ID, c.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("burned challenge reused: %v", err)
	}
	// Someone else's challenge, signed correctly by them, cannot delete us.
	oc := deleteChallenge(t, svc, other.ID)
	if err := svc.DeleteAccount(ctx, u.ID, oc.ID, sigverifytest.Sign(otherKey, DeleteMessage(other.ID, oc.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("cross-user challenge accepted: %v", err)
	}
	// A different key's signature fails.
	c = deleteChallenge(t, svc, u.ID)
	if err := svc.DeleteAccount(ctx, u.ID, c.ID, sigverifytest.Sign(otherKey, DeleteMessage(u.ID, c.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("wrong key accepted: %v", err)
	}
	// An expired challenge fails.
	c = deleteChallenge(t, svc, u.ID)
	clock.t = clock.t.Add(2 * time.Minute)
	if err := svc.DeleteAccount(ctx, u.ID, c.ID, sigverifytest.Sign(key, DeleteMessage(u.ID, c.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("expired challenge accepted: %v", err)
	}
	if _, err := svc.User(ctx, u.ID); err != nil {
		t.Fatalf("account gone after failed attempts: %v", err)
	}

	tok := login(t, svc, u, key)
	c = deleteChallenge(t, svc, u.ID)
	if err := svc.DeleteAccount(ctx, u.ID, c.ID, sigverifytest.Sign(key, DeleteMessage(u.ID, c.Nonce))); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := svc.User(ctx, u.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("user survived: %v", err)
	}
	if _, _, err := svc.Authenticate(ctx, tok); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("token survived deletion: %v", err)
	}
	if _, err := svc.Challenge(ctx, u.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("deleted user can still sign in: %v", err)
	}
	if _, err := svc.User(ctx, other.ID); err != nil {
		t.Fatalf("other user affected: %v", err)
	}
}
