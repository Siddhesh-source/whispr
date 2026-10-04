package auth

import (
	"testing"

	"whispr/server/internal/platform/dbtest"
)

// TestPGStoreContract runs the store contract against real Postgres, one
// fresh database per subtest. Skipped unless WHISPR_TEST_DATABASE_URL is set
// (CI sets it; locally, `docker compose up db`).
func TestPGStoreContract(t *testing.T) {
	storeContract(t, func(t *testing.T) Store { return NewPGStore(dbtest.New(t)) })
}
