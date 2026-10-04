package db_test

import (
	"context"
	"testing"
	"time"

	"whispr/server/internal/platform/db"
	"whispr/server/internal/platform/dbtest"
)

// TestMigrateIsIdempotentAndReleasesConnections guards against a regression
// where the migration driver kept a pooled connection checked out, making
// pool.Close (and therefore graceful shutdown) hang forever.
func TestMigrateIsIdempotentAndReleasesConnections(t *testing.T) {
	pool, err := db.Open(context.Background(), dbtest.URL(t))
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err := db.Migrate(pool); err != nil {
			pool.Close()
			t.Fatal(err)
		}
	}
	done := make(chan struct{})
	go func() { pool.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("pool.Close blocked: a connection was not released")
	}
}
