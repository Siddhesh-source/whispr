package dbtest

import (
	"context"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// TestURLPointsAtAFreshDatabase guards the isolation every integration test
// relies on: the returned URL must name a new, empty database, not the
// shared one in WHISPR_TEST_DATABASE_URL.
func TestURLPointsAtAFreshDatabase(t *testing.T) {
	a, b := URL(t), URL(t)
	ctx := context.Background()
	names := map[string]bool{}
	for _, u := range []string{a, b} {
		conn, err := pgx.Connect(ctx, u)
		if err != nil {
			t.Fatal(err)
		}
		var name string
		var tables int
		err = conn.QueryRow(ctx, `SELECT current_database(),
			(SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public')`).Scan(&name, &tables)
		_ = conn.Close(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(name, "whispr_t_") || tables != 0 {
			t.Fatalf("URL connected to %q with %d tables; want a fresh whispr_t_* database", name, tables)
		}
		names[name] = true
	}
	if len(names) != 2 {
		t.Fatal("two calls returned the same database")
	}
}
