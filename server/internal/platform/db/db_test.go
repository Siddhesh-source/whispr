package db

import (
	"context"
	"os"
	"testing"
	"time"
)

// TestMigrateIsIdempotentAndReleasesConnections guards against a regression
// where the migration driver kept a pooled connection checked out, making
// pool.Close (and therefore graceful shutdown) hang forever.
func TestMigrateIsIdempotentAndReleasesConnections(t *testing.T) {
	url := os.Getenv("WHISPR_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("WHISPR_TEST_DATABASE_URL not set")
	}
	pool, err := Open(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err := Migrate(pool); err != nil {
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
