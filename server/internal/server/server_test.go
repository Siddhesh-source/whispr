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
	"whispr/server/internal/keys"
	"whispr/server/internal/messaging"
	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/profile"
	"whispr/server/internal/push"
	"whispr/server/internal/sigverify/sigverifytest"
)

type okPinger struct{}

func (okPinger) Ping(context.Context) error { return nil }

func newRouter(rateLimit int) http.Handler {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	svc := auth.NewService(nil, sigverifytest.Fake{}, auth.Options{TokenTTL: time.Minute, ChallengeTTL: time.Minute})
	return NewRouter(Deps{
		Log:         log,
		DB:          okPinger{},
		Auth:        svc,
		RateLimiter: httpx.NewRateLimiter(rateLimit),
		Messaging:   messaging.New(messaging.NewGateway(nil, messaging.NewHub(), log, messaging.GatewayOptions{UserID: auth.UserIDFrom})),
		Contacts:    contacts.New(nil, log),
		Keys:        keys.NewModule(nil, nil, log, auth.UserIDFrom, keys.DefaultLimits()),
		Push:        push.NewModule(nil, log, auth.UserIDFrom),
		Profile:     profile.NewModule(nil, log, auth.UserIDFrom, httpx.NewRateLimiter(10).Middleware),
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
