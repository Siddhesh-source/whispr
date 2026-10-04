package keys

import (
	"bytes"
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// PGStore keeps prekeys in Postgres.
type PGStore struct{ pool *pgxpool.Pool }

func NewPGStore(pool *pgxpool.Pool) *PGStore { return &PGStore{pool: pool} }

// IdentityKey returns the user's registered identity public key.
func (s *PGStore) IdentityKey(ctx context.Context, user uuid.UUID) ([]byte, error) {
	var key []byte
	err := s.pool.QueryRow(ctx, `SELECT identity_key FROM users WHERE id = $1`, user).Scan(&key)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUnknownUser
	}
	return key, err
}

// Upload stores an already validated upload atomically. Re-uploading the
// same bytes under the same ID is a no-op; different bytes under an existing
// ID is ErrConflict, so the server can never serve a public key whose private
// half the client no longer has.
func (s *PGStore) Upload(ctx context.Context, user uuid.UUID, u Upload, now time.Time) error {
	return pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		// Serialize uploads per user so the count check below is exact.
		var current *int32
		err := tx.QueryRow(ctx, `SELECT registration_id FROM users WHERE id = $1 FOR UPDATE`, user).Scan(&current)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrUnknownUser
		}
		if err != nil {
			return err
		}
		if u.RegistrationID != nil {
			switch {
			case current == nil:
				if _, err := tx.Exec(ctx, `UPDATE users SET registration_id = $2 WHERE id = $1`, user, *u.RegistrationID); err != nil {
					return err
				}
			case *current != *u.RegistrationID:
				return ErrConflict
			}
		}
		if u.SignedPreKey != nil {
			if err := putCurrent(ctx, tx, "signed_prekeys", user, *u.SignedPreKey, now); err != nil {
				return err
			}
		}
		if u.LastResort != nil {
			if err := putCurrent(ctx, tx, "kyber_last_resort", user, *u.LastResort, now); err != nil {
				return err
			}
		}
		if len(u.OneTime) > 0 {
			signed := make([]SignedKey, len(u.OneTime))
			for i, k := range u.OneTime {
				signed[i] = SignedKey{KeyID: k.KeyID, PublicKey: k.PublicKey}
			}
			if err := putOneTime(ctx, tx, "one_time_prekeys", user, signed); err != nil {
				return err
			}
		}
		if len(u.Kyber) > 0 {
			if err := putOneTime(ctx, tx, "kyber_prekeys", user, u.Kyber); err != nil {
				return err
			}
		}
		return nil
	})
}

// putCurrent sets the single current key in table (signed_prekeys or
// kyber_last_resort). A new ID replaces the old key (rotation).
func putCurrent(ctx context.Context, tx pgx.Tx, table string, user uuid.UUID, k SignedKey, now time.Time) error {
	var id int32
	var pub, sig []byte
	err := tx.QueryRow(ctx, `SELECT key_id, public_key, signature FROM `+table+` WHERE user_id = $1`, user).
		Scan(&id, &pub, &sig)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		_, err = tx.Exec(ctx, `INSERT INTO `+table+` (user_id, key_id, public_key, signature, uploaded_at)
			VALUES ($1, $2, $3, $4, $5)`, user, k.KeyID, k.PublicKey, k.Signature, now)
		return err
	case err != nil:
		return err
	case id == k.KeyID:
		if bytes.Equal(pub, k.PublicKey) && bytes.Equal(sig, k.Signature) {
			return nil
		}
		return ErrConflict
	default:
		_, err = tx.Exec(ctx, `UPDATE `+table+` SET key_id = $2, public_key = $3, signature = $4, uploaded_at = $5
			WHERE user_id = $1`, user, k.KeyID, k.PublicKey, k.Signature, now)
		return err
	}
}

// putOneTime adds one-time keys (one_time_prekeys has no signature column).
func putOneTime(ctx context.Context, tx pgx.Tx, table string, user uuid.UUID, keys []SignedKey) error {
	ids := make([]int32, len(keys))
	for i, k := range keys {
		ids[i] = k.KeyID
	}
	rows, err := tx.Query(ctx, `SELECT key_id, public_key FROM `+table+` WHERE user_id = $1 AND key_id = ANY($2)`, user, ids)
	if err != nil {
		return err
	}
	existing := map[int32][]byte{}
	for rows.Next() {
		var id int32
		var pub []byte
		if err := rows.Scan(&id, &pub); err != nil {
			rows.Close()
			return err
		}
		existing[id] = pub
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, k := range keys {
		if pub, ok := existing[k.KeyID]; ok {
			if !bytes.Equal(pub, k.PublicKey) {
				return ErrConflict
			}
			continue
		}
		var err error
		if table == "kyber_prekeys" {
			_, err = tx.Exec(ctx, `INSERT INTO kyber_prekeys (user_id, key_id, public_key, signature) VALUES ($1, $2, $3, $4)`,
				user, k.KeyID, k.PublicKey, k.Signature)
		} else {
			_, err = tx.Exec(ctx, `INSERT INTO one_time_prekeys (user_id, key_id, public_key) VALUES ($1, $2, $3)`,
				user, k.KeyID, k.PublicKey)
		}
		if err != nil {
			return err
		}
	}
	var n int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM `+table+` WHERE user_id = $1`, user).Scan(&n); err != nil {
		return err
	}
	if n > MaxStored {
		return ErrTooMany
	}
	return nil
}

// Counts reports what the user has stored.
func (s *PGStore) Counts(ctx context.Context, user uuid.UUID) (Counts, error) {
	var c Counts
	err := s.pool.QueryRow(ctx, `
		SELECT u.registration_id,
		       (SELECT key_id FROM signed_prekeys WHERE user_id = u.id),
		       (SELECT key_id FROM kyber_last_resort WHERE user_id = u.id),
		       (SELECT count(*) FROM one_time_prekeys WHERE user_id = u.id),
		       (SELECT count(*) FROM kyber_prekeys WHERE user_id = u.id)
		FROM users u WHERE u.id = $1`, user).
		Scan(&c.RegistrationID, &c.SignedPreKeyID, &c.LastResortKeyID, &c.OneTime, &c.Kyber)
	if errors.Is(err, pgx.ErrNoRows) {
		return Counts{}, ErrUnknownUser
	}
	return c, err
}

// Bundle returns one prekey bundle for target, consuming at most one
// one-time EC key and one one-time Kyber key. Each one-time key is deleted in
// the same statement that selects it, and SKIP LOCKED makes concurrent
// requests take different rows, so no key is ever handed out twice. When no
// one-time Kyber key is left, the last-resort key is returned (and kept).
func (s *PGStore) Bundle(ctx context.Context, target uuid.UUID) (Bundle, error) {
	var b Bundle
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		var reg *int32
		err := tx.QueryRow(ctx, `SELECT identity_key, registration_id FROM users WHERE id = $1`, target).
			Scan(&b.IdentityKey, &reg)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrUnknownUser
		}
		if err != nil {
			return err
		}
		if reg == nil {
			return ErrNoKeys
		}
		b.RegistrationID = *reg
		err = tx.QueryRow(ctx, `SELECT key_id, public_key, signature FROM signed_prekeys WHERE user_id = $1`, target).
			Scan(&b.SignedPreKey.KeyID, &b.SignedPreKey.PublicKey, &b.SignedPreKey.Signature)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNoKeys
		}
		if err != nil {
			return err
		}
		var lastResort SignedKey
		err = tx.QueryRow(ctx, `SELECT key_id, public_key, signature FROM kyber_last_resort WHERE user_id = $1`, target).
			Scan(&lastResort.KeyID, &lastResort.PublicKey, &lastResort.Signature)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNoKeys
		}
		if err != nil {
			return err
		}

		var ot OneTimeKey
		err = tx.QueryRow(ctx, `
			DELETE FROM one_time_prekeys WHERE (user_id, key_id) = (
				SELECT user_id, key_id FROM one_time_prekeys WHERE user_id = $1
				ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
			RETURNING key_id, public_key`, target).Scan(&ot.KeyID, &ot.PublicKey)
		switch {
		case err == nil:
			b.OneTime = &ot
		case !errors.Is(err, pgx.ErrNoRows):
			return err
		}

		err = tx.QueryRow(ctx, `
			DELETE FROM kyber_prekeys WHERE (user_id, key_id) = (
				SELECT user_id, key_id FROM kyber_prekeys WHERE user_id = $1
				ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
			RETURNING key_id, public_key, signature`, target).Scan(&b.Kyber.KeyID, &b.Kyber.PublicKey, &b.Kyber.Signature)
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			b.Kyber = lastResort
			b.KyberLastResort = true
		case err != nil:
			return err
		}
		return nil
	})
	return b, err
}
