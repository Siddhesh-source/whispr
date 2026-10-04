// Package auth implements key-based registration and challenge-response
// authentication. There are no passwords and no phone numbers: possession of
// the libsignal identity private key is the credential.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"

	"whispr/server/internal/sigverify"
)

var (
	ErrInvalidInput = errors.New("auth: invalid input")
	// ErrAuthFailed covers unknown/expired/used challenges and bad
	// signatures alike, so callers cannot distinguish them.
	ErrAuthFailed   = errors.New("auth: authentication failed")
	ErrUnauthorized = errors.New("auth: unauthorized")
)

const (
	tokenLen          = 32
	maxDisplayNameLen = 64
)

type Service struct {
	store        Store
	verifier     sigverify.Verifier
	tokenTTL     time.Duration
	challengeTTL time.Duration
	now          func() time.Time
	rand         io.Reader
}

type Options struct {
	TokenTTL     time.Duration
	ChallengeTTL time.Duration
	// Now and Rand are overridable for tests.
	Now  func() time.Time
	Rand io.Reader
}

func NewService(store Store, verifier sigverify.Verifier, opts Options) *Service {
	s := &Service{
		store:        store,
		verifier:     verifier,
		tokenTTL:     opts.TokenTTL,
		challengeTTL: opts.ChallengeTTL,
		now:          opts.Now,
		rand:         opts.Rand,
	}
	if s.now == nil {
		s.now = time.Now
	}
	if s.rand == nil {
		s.rand = rand.Reader
	}
	return s
}

// Register creates a user for identityKey, or returns the existing one if the
// key is already registered (making client retries safe). The signature
// proves possession of the private key.
func (s *Service) Register(ctx context.Context, identityKey []byte, displayName string, signature []byte) (User, bool, error) {
	if err := ValidateDisplayName(displayName); err != nil {
		return User{}, false, err
	}
	if err := s.verifier.ValidatePublicKey(identityKey); err != nil {
		return User{}, false, ErrInvalidInput
	}
	ok, err := s.verifier.Verify(identityKey, RegisterMessage(identityKey, displayName), signature)
	if err != nil || !ok {
		return User{}, false, ErrAuthFailed
	}
	return s.store.CreateUser(ctx, User{ID: uuid.New(), IdentityKey: identityKey, DisplayName: displayName})
}

// Challenge issues a single-use nonce for userID.
func (s *Service) Challenge(ctx context.Context, userID uuid.UUID) (Challenge, error) {
	if _, err := s.store.GetUser(ctx, userID); err != nil {
		if errors.Is(err, ErrNotFound) {
			return Challenge{}, ErrNotFound
		}
		return Challenge{}, err
	}
	nonce := make([]byte, NonceLen)
	if _, err := io.ReadFull(s.rand, nonce); err != nil {
		return Challenge{}, fmt.Errorf("generate nonce: %w", err)
	}
	c := Challenge{
		ID:        uuid.New(),
		UserID:    userID,
		Nonce:     nonce,
		ExpiresAt: s.now().Add(s.challengeTTL),
	}
	if err := s.store.CreateChallenge(ctx, c); err != nil {
		return Challenge{}, err
	}
	return c, nil
}

// Verify consumes the challenge, then checks the signature. The challenge is
// burned even if the signature is wrong, so each nonce gets one attempt.
func (s *Service) Verify(ctx context.Context, challengeID uuid.UUID, signature []byte) (token string, expiresAt time.Time, err error) {
	now := s.now()
	c, err := s.store.ConsumeChallenge(ctx, challengeID, now)
	if errors.Is(err, ErrNotFound) {
		return "", time.Time{}, ErrAuthFailed
	} else if err != nil {
		return "", time.Time{}, err
	}
	u, err := s.store.GetUser(ctx, c.UserID)
	if err != nil {
		return "", time.Time{}, err
	}
	ok, err := s.verifier.Verify(u.IdentityKey, AuthMessage(u.ID, c.Nonce), signature)
	if err != nil || !ok {
		return "", time.Time{}, ErrAuthFailed
	}

	raw := make([]byte, tokenLen)
	if _, err := io.ReadFull(s.rand, raw); err != nil {
		return "", time.Time{}, fmt.Errorf("generate token: %w", err)
	}
	expiresAt = now.Add(s.tokenTTL)
	if err := s.store.CreateToken(ctx, hashToken(raw), u.ID, expiresAt); err != nil {
		return "", time.Time{}, err
	}
	return base64.RawURLEncoding.EncodeToString(raw), expiresAt, nil
}

// Authenticate resolves a bearer token to its user ID.
func (s *Service) Authenticate(ctx context.Context, token string) (uuid.UUID, error) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) != tokenLen {
		return uuid.Nil, ErrUnauthorized
	}
	id, err := s.store.LookupToken(ctx, hashToken(raw), s.now())
	if errors.Is(err, ErrNotFound) {
		return uuid.Nil, ErrUnauthorized
	}
	return id, err
}

func (s *Service) User(ctx context.Context, id uuid.UUID) (User, error) {
	return s.store.GetUser(ctx, id)
}

// RunJanitor deletes expired challenges and tokens every interval until ctx
// is cancelled.
func (s *Service) RunJanitor(ctx context.Context, interval time.Duration, log *slog.Logger) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if err := s.store.DeleteExpired(ctx, s.now()); err != nil && ctx.Err() == nil {
				log.Error("auth janitor failed", "err", err)
			}
		}
	}
}

func hashToken(raw []byte) []byte {
	h := sha256.Sum256(raw)
	return h[:]
}

// ValidateDisplayName enforces 1–64 characters of valid UTF-8, no leading or
// trailing whitespace (clients trim before signing), no control characters,
// and no bidi override characters (which enable name spoofing).
func ValidateDisplayName(name string) error {
	if !utf8.ValidString(name) || name == "" || strings.TrimSpace(name) != name {
		return ErrInvalidInput
	}
	if utf8.RuneCountInString(name) > maxDisplayNameLen {
		return ErrInvalidInput
	}
	for _, r := range name {
		if unicode.IsControl(r) || isBidiControl(r) {
			return ErrInvalidInput
		}
	}
	return nil
}

func isBidiControl(r rune) bool {
	return (r >= 0x202A && r <= 0x202E) || (r >= 0x2066 && r <= 0x2069) || r == 0x200E || r == 0x200F || r == 0x061C
}
