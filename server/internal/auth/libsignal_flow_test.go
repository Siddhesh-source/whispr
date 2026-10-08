//go:build libsignal

package auth

import (
	"context"
	"testing"
	"time"

	"whispr/server/internal/sigverify"
)

// TestFullFlowWithRealLibsignal exercises register → challenge → verify with
// genuine libsignal keys and signatures (the same scheme Android uses via
// IdentityKeyPair.privateKey.calculateSignature).
func TestFullFlowWithRealLibsignal(t *testing.T) {
	v, err := sigverify.New()
	if err != nil {
		t.Fatal(err)
	}
	kp, err := sigverify.NewTestKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	defer kp.Close()

	svc := NewService(newMemStore(), v, Options{TokenTTL: time.Minute, ChallengeTTL: time.Minute})
	ctx := context.Background()

	regSig, err := kp.Sign(RegisterMessage(kp.Public, "Ada"))
	if err != nil {
		t.Fatal(err)
	}
	u, created, err := svc.Register(ctx, kp.Public, "Ada", regSig)
	if err != nil || !created {
		t.Fatalf("register: %v", err)
	}

	c, err := svc.Challenge(ctx, u.ID)
	if err != nil {
		t.Fatal(err)
	}
	sig, err := kp.Sign(AuthMessage(u.ID, c.Nonce))
	if err != nil {
		t.Fatal(err)
	}
	tok, _, err := svc.Verify(ctx, c.ID, sig)
	if err != nil {
		t.Fatalf("verify: %v", err)
	}
	if id, _, err := svc.Authenticate(ctx, tok); err != nil || id != u.ID {
		t.Fatalf("authenticate: %v %v", id, err)
	}
}
