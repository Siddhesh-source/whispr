// Package health serves the liveness/readiness endpoint.
package health

import (
	"context"
	"net/http"
	"time"

	"whispr/server/internal/platform/httpx"
)

// Pinger is satisfied by *pgxpool.Pool.
type Pinger interface {
	Ping(ctx context.Context) error
}

type response struct {
	Status   string `json:"status"`
	Database string `json:"database"`
}

// Handler reports 200 when the database is reachable and 503 otherwise.
func Handler(db Pinger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := db.Ping(ctx); err != nil {
			httpx.WriteJSON(w, http.StatusServiceUnavailable, response{Status: "unavailable", Database: "down"})
			return
		}
		httpx.WriteJSON(w, http.StatusOK, response{Status: "ok", Database: "up"})
	}
}
