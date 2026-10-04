package keys

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/golang-migrate/migrate/v4"
	migratepgx "github.com/golang-migrate/migrate/v4/database/pgx/v5"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"

	"whispr/server/internal/platform/db"
	"whispr/server/internal/platform/dbtest"
	"whispr/server/migrations"
)

var now = time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)

// newUser inserts a user and returns its ID and identity key.
func newUser(t *testing.T, pool *pgxpool.Pool) (uuid.UUID, []byte) {
	t.Helper()
	id, key := uuid.New(), ecKey()
	if _, err := pool.Exec(context.Background(),
		`INSERT INTO users (id, identity_key, display_name) VALUES ($1, $2, 'u')`, id, key); err != nil {
		t.Fatal(err)
	}
	return id, key
}

func TestUploadCountsAndIdempotentReupload(t *testing.T) {
	s := NewPGStore(dbtest.New(t))
	ctx := context.Background()
	user, ik := newUser(t, s.pool)
	u := fullUpload(ik, 10, 5)
	if err := s.Upload(ctx, user, u, now); err != nil {
		t.Fatal(err)
	}
	// Exactly the same bytes again (a retried request) changes nothing.
	if err := s.Upload(ctx, user, u, now); err != nil {
		t.Fatalf("identical re-upload: %v", err)
	}
	c, err := s.Counts(ctx, user)
	if err != nil {
		t.Fatal(err)
	}
	if c.OneTime != 5 || c.Kyber != 5 || *c.RegistrationID != 1234 || *c.SignedPreKeyID != 1 || *c.LastResortKeyID != 1 {
		t.Fatalf("counts = %+v", c)
	}
}

func TestDifferentKeyUnderExistingIDIsAConflict(t *testing.T) {
	s := NewPGStore(dbtest.New(t))
	ctx := context.Background()
	user, ik := newUser(t, s.pool)
	if err := s.Upload(ctx, user, fullUpload(ik, 1, 2), now); err != nil {
		t.Fatal(err)
	}
	sp := signed(ik, 1, ecKey())
	lr := signed(ik, 1, kyberKey())
	ot, ky := oneTimeKeys(ik, 1, 1)
	reg := int32(99)
	for name, u := range map[string]Upload{
		"signed prekey":   {SignedPreKey: &sp},
		"last resort":     {LastResort: &lr},
		"one-time ec":     {OneTime: ot},
		"one-time kyber":  {Kyber: ky},
		"registration id": {RegistrationID: &reg},
	} {
		if err := s.Upload(ctx, user, u, now); !errors.Is(err, ErrConflict) {
			t.Errorf("%s: err = %v, want ErrConflict", name, err)
		}
	}
}

func TestRotationReplacesTheCurrentSignedAndLastResortKeys(t *testing.T) {
	s := NewPGStore(dbtest.New(t))
	ctx := context.Background()
	user, ik := newUser(t, s.pool)
	if err := s.Upload(ctx, user, fullUpload(ik, 1, 1), now); err != nil {
		t.Fatal(err)
	}
	sp := signed(ik, 2, ecKey())
	lr := signed(ik, 2, kyberKey())
	if err := s.Upload(ctx, user, Upload{SignedPreKey: &sp, LastResort: &lr}, now); err != nil {
		t.Fatal(err)
	}
	b, err := s.Bundle(ctx, user)
	if err != nil {
		t.Fatal(err)
	}
	if b.SignedPreKey.KeyID != 2 || string(b.SignedPreKey.PublicKey) != string(sp.PublicKey) {
		t.Fatalf("signed prekey not rotated: %+v", b.SignedPreKey.KeyID)
	}
}

func TestStoredOneTimeKeysAreCappedAtomically(t *testing.T) {
	s := NewPGStore(dbtest.New(t))
	ctx := context.Background()
	user, ik := newUser(t, s.pool)
	for first := int32(0); first < MaxStored; first += MaxBatch {
		ot, ky := oneTimeKeys(ik, first, MaxBatch)
		if err := s.Upload(ctx, user, Upload{OneTime: ot, Kyber: ky}, now); err != nil {
			t.Fatal(err)
		}
	}
	ot, _ := oneTimeKeys(ik, MaxStored, 1)
	sp := signed(ik, 7, ecKey())
	if err := s.Upload(ctx, user, Upload{OneTime: ot, SignedPreKey: &sp}, now); !errors.Is(err, ErrTooMany) {
		t.Fatalf("err = %v, want ErrTooMany", err)
	}
	c, _ := s.Counts(ctx, user)
	if c.OneTime != MaxStored || c.SignedPreKeyID != nil {
		t.Fatalf("rejected upload was partly applied: %+v", c)
	}
}

func TestBundleRequiresACompleteKeySet(t *testing.T) {
	s := NewPGStore(dbtest.New(t))
	ctx := context.Background()
	if _, err := s.Bundle(ctx, uuid.New()); !errors.Is(err, ErrUnknownUser) {
		t.Fatalf("unknown user: %v", err)
	}
	user, ik := newUser(t, s.pool)
	if _, err := s.Bundle(ctx, user); !errors.Is(err, ErrNoKeys) {
		t.Fatalf("never uploaded: %v", err)
	}
	reg := int32(5)
	sp := signed(ik, 1, ecKey())
	if err := s.Upload(ctx, user, Upload{RegistrationID: &reg, SignedPreKey: &sp}, now); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Bundle(ctx, user); !errors.Is(err, ErrNoKeys) {
		t.Fatalf("missing last-resort key: %v", err)
	}
}

func TestBundleConsumesOneTimeKeysThenFallsBackToLastResort(t *testing.T) {
	s := NewPGStore(dbtest.New(t))
	ctx := context.Background()
	user, ik := newUser(t, s.pool)
	u := fullUpload(ik, 1, 2)
	u.OneTime = u.OneTime[:1] // one EC key, two Kyber keys
	if err := s.Upload(ctx, user, u, now); err != nil {
		t.Fatal(err)
	}

	b1, err := s.Bundle(ctx, user)
	if err != nil {
		t.Fatal(err)
	}
	if b1.OneTime == nil || b1.OneTime.KeyID != 1 || b1.Kyber.KeyID != 1 || b1.KyberLastResort {
		t.Fatalf("first bundle: %+v", b1)
	}
	if string(b1.IdentityKey) != string(ik) || b1.RegistrationID != 1234 {
		t.Fatal("bundle identity or registration id wrong")
	}
	b2, _ := s.Bundle(ctx, user)
	if b2.OneTime != nil || b2.Kyber.KeyID != 2 || b2.KyberLastResort {
		t.Fatalf("second bundle: one-time %v kyber %d", b2.OneTime, b2.Kyber.KeyID)
	}
	for range 3 {
		b, err := s.Bundle(ctx, user)
		if err != nil {
			t.Fatal(err)
		}
		if !b.KyberLastResort || string(b.Kyber.PublicKey) != string(u.LastResort.PublicKey) {
			t.Fatal("expected the last-resort key once one-time keys ran out")
		}
	}
	c, _ := s.Counts(ctx, user)
	if c.OneTime != 0 || c.Kyber != 0 || *c.LastResortKeyID != 1 {
		t.Fatalf("counts after use = %+v", c)
	}
}

func TestConcurrentBundlesNeverHandOutTheSameOneTimeKey(t *testing.T) {
	s := NewPGStore(dbtest.New(t))
	ctx := context.Background()
	user, ik := newUser(t, s.pool)
	const stored, fetches = 30, 50
	if err := s.Upload(ctx, user, fullUpload(ik, 1, stored), now); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	ec, kyber := map[int32]int{}, map[int32]int{}
	var wg sync.WaitGroup
	for range fetches {
		wg.Go(func() {
			b, err := s.Bundle(ctx, user)
			if err != nil {
				t.Error(err)
				return
			}
			mu.Lock()
			defer mu.Unlock()
			if b.OneTime != nil {
				ec[b.OneTime.KeyID]++
			}
			if !b.KyberLastResort {
				kyber[b.Kyber.KeyID]++
			}
		})
	}
	wg.Wait()
	for id, n := range ec {
		if n > 1 {
			t.Errorf("one-time EC key %d handed out %d times", id, n)
		}
	}
	for id, n := range kyber {
		if n > 1 {
			t.Errorf("one-time Kyber key %d handed out %d times", id, n)
		}
	}
	// SKIP LOCKED may hand some requests the last-resort key while rows are
	// locked by others, but never a key twice, and the rest stay stored.
	c, _ := s.Counts(ctx, user)
	if len(ec)+c.OneTime != stored || len(kyber)+c.Kyber != stored {
		t.Fatalf("keys lost: ec handed %d + left %d, kyber handed %d + left %d", len(ec), c.OneTime, len(kyber), c.Kyber)
	}
}

// TestMigrationPurgesPlaintextEnvelopes checks 0004 deletes envelopes stored
// before payloads became ciphertext, and that it rolls back cleanly.
func TestMigrationPurgesPlaintextEnvelopes(t *testing.T) {
	ctx := context.Background()
	pool, err := db.Open(ctx, dbtest.URL(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	m := newMigrator(t, pool)
	if err := m.Migrate(3); err != nil {
		t.Fatal(err)
	}
	a, b := uuid.New(), uuid.New()
	if _, err := pool.Exec(ctx, `INSERT INTO users (id, identity_key, display_name) VALUES ($1, $3, 'a'), ($2, $4, 'b')`,
		a, b, ecKey(), ecKey()); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `
		INSERT INTO envelopes (message_id, conversation_id, sender_id, recipient_id, kind, client_ts, server_ts, payload)
		VALUES (gen_random_uuid(), gen_random_uuid(), $1, $2, 1, now(), now(), '{"t":"text","body":"plaintext"}')`,
		a, b); err != nil {
		t.Fatal(err)
	}
	if err := m.Migrate(4); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM envelopes`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("envelopes after 0004 = %d (%v), want 0", n, err)
	}
	if err := m.Migrate(3); err != nil {
		t.Fatalf("down migration: %v", err)
	}
}

func newMigrator(t *testing.T, pool *pgxpool.Pool) *migrate.Migrate {
	t.Helper()
	src, err := iofs.New(migrations.FS, ".")
	if err != nil {
		t.Fatal(err)
	}
	sqlDB := stdlib.OpenDBFromPool(pool)
	driver, err := migratepgx.WithInstance(sqlDB, &migratepgx.Config{})
	if err != nil {
		t.Fatal(err)
	}
	m, err := migrate.NewWithInstance("iofs", src, "pgx5", driver)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = m.Close()
		_ = sqlDB.Close()
	})
	return m
}
