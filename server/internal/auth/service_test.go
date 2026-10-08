package auth

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"whispr/server/internal/sigverify/sigverifytest"
)

type fakeClock struct{ t time.Time }

func (c *fakeClock) Now() time.Time { return c.t }

func newTestService(t *testing.T) (*Service, *fakeClock) {
	t.Helper()
	clock := &fakeClock{t: time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)}
	svc := NewService(newMemStore(), sigverifytest.Fake{}, Options{
		TokenTTL:     15 * time.Minute,
		ChallengeTTL: time.Minute,
		Now:          clock.Now,
	})
	return svc, clock
}

func register(t *testing.T, svc *Service, name string) (User, []byte) {
	t.Helper()
	key := sigverifytest.NewPublicKey()
	u, _, err := svc.Register(context.Background(), key, name, sigverifytest.Sign(key, RegisterMessage(key, name)))
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	return u, key
}

func login(t *testing.T, svc *Service, u User, key []byte) string {
	t.Helper()
	ctx := context.Background()
	c, err := svc.Challenge(ctx, u.ID)
	if err != nil {
		t.Fatal(err)
	}
	tok, _, err := svc.Verify(ctx, c.ID, sigverifytest.Sign(key, AuthMessage(u.ID, c.Nonce)))
	if err != nil {
		t.Fatalf("verify: %v", err)
	}
	return tok
}

func TestRegisterAndAuthenticate(t *testing.T) {
	svc, _ := newTestService(t)
	u, key := register(t, svc, "Ada")
	tok := login(t, svc, u, key)

	id, _, err := svc.Authenticate(context.Background(), tok)
	if err != nil || id != u.ID {
		t.Fatalf("Authenticate = %v, %v", id, err)
	}
}

func TestRegisterRetryReturnsSameUser(t *testing.T) {
	svc, _ := newTestService(t)
	key := sigverifytest.NewPublicKey()
	sig := sigverifytest.Sign(key, RegisterMessage(key, "Ada"))
	a, created1, _ := svc.Register(context.Background(), key, "Ada", sig)
	b, created2, err := svc.Register(context.Background(), key, "Ada", sig)
	if err != nil || a.ID != b.ID || !created1 || created2 {
		t.Fatalf("a=%v b=%v created=%v,%v err=%v", a.ID, b.ID, created1, created2, err)
	}
}

func TestRegisterRejectsSignatureForDifferentName(t *testing.T) {
	svc, _ := newTestService(t)
	key := sigverifytest.NewPublicKey()
	sig := sigverifytest.Sign(key, RegisterMessage(key, "Ada"))
	if _, _, err := svc.Register(context.Background(), key, "Mallory", sig); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("err = %v", err)
	}
}

func TestRegisterRejectsMalformedKey(t *testing.T) {
	svc, _ := newTestService(t)
	key := make([]byte, 32)
	if _, _, err := svc.Register(context.Background(), key, "Ada", sigverifytest.Sign(key, RegisterMessage(key, "Ada"))); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("err = %v", err)
	}
}

func TestVerifyRejectsWrongKey(t *testing.T) {
	svc, _ := newTestService(t)
	u, _ := register(t, svc, "Ada")
	c, _ := svc.Challenge(context.Background(), u.ID)
	attacker := sigverifytest.NewPublicKey()
	if _, _, err := svc.Verify(context.Background(), c.ID, sigverifytest.Sign(attacker, AuthMessage(u.ID, c.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("err = %v", err)
	}
}

func TestVerifyBurnsChallengeOnFailure(t *testing.T) {
	svc, _ := newTestService(t)
	u, key := register(t, svc, "Ada")
	ctx := context.Background()
	c, _ := svc.Challenge(ctx, u.ID)

	if _, _, err := svc.Verify(ctx, c.ID, []byte("garbage")); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("bad sig err = %v", err)
	}
	// The correct signature is now useless: one attempt per nonce.
	if _, _, err := svc.Verify(ctx, c.ID, sigverifytest.Sign(key, AuthMessage(u.ID, c.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("replay after failure err = %v", err)
	}
}

func TestVerifyRejectsReplay(t *testing.T) {
	svc, _ := newTestService(t)
	u, key := register(t, svc, "Ada")
	ctx := context.Background()
	c, _ := svc.Challenge(ctx, u.ID)
	sig := sigverifytest.Sign(key, AuthMessage(u.ID, c.Nonce))
	if _, _, err := svc.Verify(ctx, c.ID, sig); err != nil {
		t.Fatal(err)
	}
	if _, _, err := svc.Verify(ctx, c.ID, sig); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("replay err = %v", err)
	}
}

func TestVerifyRejectsExpiredChallenge(t *testing.T) {
	svc, clock := newTestService(t)
	u, key := register(t, svc, "Ada")
	ctx := context.Background()
	c, _ := svc.Challenge(ctx, u.ID)
	clock.t = clock.t.Add(61 * time.Second)
	if _, _, err := svc.Verify(ctx, c.ID, sigverifytest.Sign(key, AuthMessage(u.ID, c.Nonce))); !errors.Is(err, ErrAuthFailed) {
		t.Fatalf("err = %v", err)
	}
}

func TestRegisterSignatureCannotBeUsedForAuth(t *testing.T) {
	// Domain separation: the two message types never collide.
	key := sigverifytest.NewPublicKey()
	id := uuid.New()
	if string(RegisterMessage(key, "x")) == string(AuthMessage(id, make([]byte, NonceLen))) {
		t.Fatal("messages collide")
	}
	if !strings.HasPrefix(string(AuthMessage(id, nil)), "whispr-auth-v1\x00") {
		t.Fatal("auth label changed; this is a wire-format break")
	}
}

func TestTokenExpires(t *testing.T) {
	svc, clock := newTestService(t)
	u, key := register(t, svc, "Ada")
	tok := login(t, svc, u, key)
	clock.t = clock.t.Add(15 * time.Minute)
	if _, _, err := svc.Authenticate(context.Background(), tok); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("err = %v", err)
	}
}

func TestAuthenticateRejectsMalformedTokens(t *testing.T) {
	svc, _ := newTestService(t)
	for _, tok := range []string{"", "!!!", "c2hvcnQ", strings.Repeat("A", 43)} {
		if _, _, err := svc.Authenticate(context.Background(), tok); !errors.Is(err, ErrUnauthorized) {
			t.Errorf("token %q: err = %v", tok, err)
		}
	}
}

func TestChallengeUnknownUser(t *testing.T) {
	svc, _ := newTestService(t)
	if _, err := svc.Challenge(context.Background(), uuid.New()); !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v", err)
	}
}

func TestValidateDisplayName(t *testing.T) {
	valid := []string{"Ada", "José", "李小龍", "🙂 Sam", strings.Repeat("a", 64)}
	invalid := []string{"", " Ada", "Ada ", "A\nB", "A\x00B", "evil\u202egnp.exe", strings.Repeat("a", 65), "\xff"}
	for _, n := range valid {
		if err := ValidateDisplayName(n); err != nil {
			t.Errorf("%q rejected", n)
		}
	}
	for _, n := range invalid {
		if err := ValidateDisplayName(n); err == nil {
			t.Errorf("%q accepted", n)
		}
	}
}
