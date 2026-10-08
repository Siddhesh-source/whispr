package auth

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type PGStore struct {
	pool *pgxpool.Pool
}

func NewPGStore(pool *pgxpool.Pool) *PGStore {
	return &PGStore{pool: pool}
}

var _ Store = (*PGStore)(nil)

func (s *PGStore) CreateUser(ctx context.Context, u User) (User, bool, error) {
	err := s.pool.QueryRow(ctx, `
		INSERT INTO users (id, identity_key, display_name)
		VALUES ($1, $2, $3)
		ON CONFLICT (identity_key) DO NOTHING
		RETURNING created_at`,
		u.ID, u.IdentityKey, u.DisplayName,
	).Scan(&u.CreatedAt)
	if err == nil {
		return u, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return User{}, false, err
	}
	existing, err := s.scanUser(s.pool.QueryRow(ctx, `
		SELECT id, identity_key, display_name, created_at FROM users WHERE identity_key = $1`,
		u.IdentityKey))
	return existing, false, err
}

func (s *PGStore) GetUser(ctx context.Context, id uuid.UUID) (User, error) {
	return s.scanUser(s.pool.QueryRow(ctx, `
		SELECT id, identity_key, display_name, created_at FROM users WHERE id = $1`, id))
}

func (s *PGStore) scanUser(row pgx.Row) (User, error) {
	var u User
	err := row.Scan(&u.ID, &u.IdentityKey, &u.DisplayName, &u.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, ErrNotFound
	}
	return u, err
}

func (s *PGStore) CreateChallenge(ctx context.Context, c Challenge) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO auth_challenges (id, user_id, nonce, expires_at) VALUES ($1, $2, $3, $4)`,
		c.ID, c.UserID, c.Nonce, c.ExpiresAt)
	return err
}

func (s *PGStore) ConsumeChallenge(ctx context.Context, id uuid.UUID, now time.Time) (Challenge, error) {
	var c Challenge
	err := s.pool.QueryRow(ctx, `
		UPDATE auth_challenges SET used_at = $2
		WHERE id = $1 AND used_at IS NULL AND expires_at > $2
		RETURNING id, user_id, nonce, expires_at`,
		id, now,
	).Scan(&c.ID, &c.UserID, &c.Nonce, &c.ExpiresAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Challenge{}, ErrNotFound
	}
	return c, err
}

func (s *PGStore) CreateToken(ctx context.Context, tokenHash []byte, userID uuid.UUID, expiresAt time.Time) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO auth_tokens (token_hash, user_id, expires_at) VALUES ($1, $2, $3)`,
		tokenHash, userID, expiresAt)
	return err
}

func (s *PGStore) LookupToken(ctx context.Context, tokenHash []byte, now time.Time) (uuid.UUID, time.Time, error) {
	var id uuid.UUID
	var exp time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT user_id, expires_at FROM auth_tokens WHERE token_hash = $1 AND expires_at > $2`,
		tokenHash, now,
	).Scan(&id, &exp)
	if errors.Is(err, pgx.ErrNoRows) {
		return uuid.Nil, time.Time{}, ErrNotFound
	}
	return id, exp, err
}

func (s *PGStore) DeleteToken(ctx context.Context, tokenHash []byte) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM auth_tokens WHERE token_hash = $1`, tokenHash)
	return err
}

func (s *PGStore) DeleteUser(ctx context.Context, id uuid.UUID) error {
	tag, err := s.pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PGStore) DeleteExpired(ctx context.Context, now time.Time) error {
	// Challenges are kept briefly after use only until they expire; there is
	// no reason to retain them longer.
	if _, err := s.pool.Exec(ctx, `DELETE FROM auth_challenges WHERE expires_at <= $1`, now); err != nil {
		return err
	}
	_, err := s.pool.Exec(ctx, `DELETE FROM auth_tokens WHERE expires_at <= $1`, now)
	return err
}
