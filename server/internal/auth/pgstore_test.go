package auth

import (
	"context"
	"os"
	"testing"

	"whispr/server/internal/platform/db"
)

// TestPGStoreContract runs against a real Postgres when
// WHISPR_TEST_DATABASE_URL is set (CI sets it; locally, `docker compose up db`).
func TestPGStoreContract(t *testing.T) {
	url := os.Getenv("WHISPR_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("WHISPR_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	pool, err := db.Open(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if err := db.Migrate(pool); err != nil {
		t.Fatal(err)
	}
	storeContract(t, func(t *testing.T) Store {
		if _, err := pool.Exec(ctx, `TRUNCATE users CASCADE`); err != nil {
			t.Fatal(err)
		}
		return NewPGStore(pool)
	})
}
