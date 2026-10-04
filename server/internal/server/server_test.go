package server

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"whispr/server/internal/auth"
	"whispr/server/internal/contacts"
	"whispr/server/internal/messaging"
	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/sigverify/sigverifytest"
)

type okPinger struct{}

func (okPinger) Ping(context.Context) error { return nil }

func newRouter(rateLimit int) http.Handler {
	svc := auth.NewService(nil, sigverifytest.Fake{}, auth.Options{TokenTTL: time.Minute, ChallengeTTL: time.Minute})
	return NewRouter(Deps{
		Log:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		DB:          okPinger{},
		Auth:        svc,
		RateLimiter: httpx.NewRateLimiter(rateLimit),
		Messaging:   messaging.New(),
		Contacts:    contacts.New(),
	})
}

func TestHealthzRoute(t *testing.T) {
	rec := httptest.NewRecorder()
	newRouter(10).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
}

func TestAuthEndpointsAreRateLimited(t *testing.T) {
	r := newRouter(2)
	var last int
	for range 3 {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/v1/auth/challenge", strings.NewReader("{}"))
		req.RemoteAddr = "192.0.2.1:1234"
		r.ServeHTTP(rec, req)
		last = rec.Code
	}
	if last != http.StatusTooManyRequests {
		t.Fatalf("third request status %d", last)
	}
}

func TestMeRequiresAuth(t *testing.T) {
	rec := httptest.NewRecorder()
	newRouter(10).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/v1/me", nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status %d", rec.Code)
	}
}
