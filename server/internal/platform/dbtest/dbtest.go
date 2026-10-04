// Package dbtest gives each test its own throwaway Postgres database.
//
// Tests run in parallel across packages (go test ./...), so sharing one
// database and truncating it is racy. Instead every test creates a uniquely
// named database on the server at WHISPR_TEST_DATABASE_URL, migrates it, and
// drops it when the test ends. The database named in the URL is only used to
// issue CREATE/DROP DATABASE and is never modified.
package dbtest

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"whispr/server/internal/platform/db"
)

// EnvVar names the admin connection URL; tests are skipped when it is unset.
const EnvVar = "WHISPR_TEST_DATABASE_URL"

// URL creates an empty database and returns its connection URL. The database
// is dropped when the test ends.
func URL(t testing.TB) string {
	t.Helper()
	base := lookup(t)
	cfg, err := pgx.ParseConfig(base)
	if err != nil {
		t.Fatalf("dbtest: parse %s: %v", EnvVar, err)
	}
	name := "whispr_t_" + strings.ReplaceAll(uuid.NewString(), "-", "")[:20]
	admin(t, base, "CREATE DATABASE "+pgx.Identifier{name}.Sanitize())
	t.Cleanup(func() {
		admin(t, base, "DROP DATABASE IF EXISTS "+pgx.Identifier{name}.Sanitize()+" WITH (FORCE)")
	})
	cfg.Database = name
	return cfg.ConnString()
}

// New returns a pool connected to a fresh, fully migrated database. The pool
// is closed and the database dropped when the test ends.
func New(t testing.TB) *pgxpool.Pool {
	t.Helper()
	url := URL(t)
	pool, err := db.Open(context.Background(), url)
	if err != nil {
		t.Fatalf("dbtest: open: %v", err)
	}
	t.Cleanup(pool.Close) // registered after the drop, so it runs first
	if err := db.Migrate(pool); err != nil {
		t.Fatalf("dbtest: migrate: %v", err)
	}
	return pool
}

func lookup(t testing.TB) string {
	url := strings.TrimSpace(os.Getenv(EnvVar))
	if url == "" {
		t.Skip(EnvVar + " not set")
	}
	return url
}

func admin(t testing.TB, url, stmt string) {
	t.Helper()
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, url)
	if err != nil {
		t.Fatalf("dbtest: connect: %v", err)
	}
	defer func() { _ = conn.Close(ctx) }()
	if _, err := conn.Exec(ctx, stmt); err != nil {
		t.Fatalf("dbtest: %s: %v", stmt, err)
	}
}
