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

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

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

// publicRoutes are the only /v1 routes reachable without a bearer token.
var publicRoutes = map[string]bool{
	"POST /v1/register":       true,
	"POST /v1/auth/challenge": true,
	"POST /v1/auth/verify":    true,
}

// TestEveryOtherRouteRequiresAuth walks the real router, so a route added
// later without auth fails here rather than in production.
func TestEveryOtherRouteRequiresAuth(t *testing.T) {
	r := newRouter(1000)
	routes, ok := r.(chi.Routes)
	if !ok {
		t.Fatal("router does not expose its routes")
	}
	var checked int
	err := chi.Walk(routes, func(method, route string, _ http.Handler, _ ...func(http.Handler) http.Handler) error {
		if !strings.HasPrefix(route, "/v1/") || publicRoutes[method+" "+route] {
			return nil
		}
		path := strings.NewReplacer("{id}", uuid.NewString(), "{username}", "sam.42", "*", "").Replace(route)
		for name, header := range map[string]string{
			"no token":        "",
			"not bearer":      "Basic abc",
			"empty bearer":    "Bearer ",
			"malformed token": "Bearer not-base64!",
			"short token":     "Bearer AAAA",
		} {
			req := httptest.NewRequest(method, path, strings.NewReader("{}"))
			if header != "" {
				req.Header.Set("Authorization", header)
			}
			rec := httptest.NewRecorder()
			r.ServeHTTP(rec, req)
			if rec.Code != http.StatusUnauthorized {
				t.Errorf("%s %s with %s: status %d, want 401", method, route, name, rec.Code)
			}
		}
		checked++
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if checked < 15 {
		t.Fatalf("only %d authenticated routes found; did the walk break?", checked)
	}
}

func TestSecurityHeadersOnEveryResponse(t *testing.T) {
	r := newRouter(10)
	for _, path := range []string{"/healthz", "/v1/me", "/nope"} {
		rec := httptest.NewRecorder()
		r.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		for k, want := range map[string]string{
			"X-Content-Type-Options":  "nosniff",
			"X-Frame-Options":         "DENY",
			"Referrer-Policy":         "no-referrer",
			"Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
		} {
			if got := rec.Header().Get(k); got != want {
				t.Errorf("%s: %s = %q, want %q", path, k, got, want)
			}
		}
	}
}

func TestRegistrationHasItsOwnStricterLimit(t *testing.T) {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	svc := auth.NewService(nil, sigverifytest.Fake{}, auth.Options{TokenTTL: time.Minute, ChallengeTTL: time.Minute})
	r := NewRouter(Deps{
		Log: log, DB: okPinger{}, Auth: svc,
		RateLimiter:     httpx.NewRateLimiter(1000),
		RegisterLimiter: httpx.NewRateLimiterPer(2, time.Hour),
		Messaging:       messaging.New(messaging.NewGateway(nil, messaging.NewHub(), log, messaging.GatewayOptions{UserID: auth.UserIDFrom})),
		Contacts:        contacts.New(nil, log),
		Keys:            keys.NewModule(nil, nil, log, auth.UserIDFrom, keys.DefaultLimits()),
		Push:            push.NewModule(nil, log, auth.UserIDFrom),
		Profile:         profile.NewModule(nil, log, auth.UserIDFrom, httpx.NewRateLimiter(10).Middleware),
	})
	post := func(path string) int {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, path, strings.NewReader("{}"))
		req.RemoteAddr = "198.51.100.9:1"
		r.ServeHTTP(rec, req)
		return rec.Code
	}
	for range 2 {
		if c := post("/v1/register"); c != http.StatusBadRequest {
			t.Fatalf("register status %d", c)
		}
	}
	if c := post("/v1/register"); c != http.StatusTooManyRequests {
		t.Fatalf("third register status %d, want 429", c)
	}
	// Signing in is not affected by the registration limit.
	if c := post("/v1/auth/challenge"); c != http.StatusBadRequest {
		t.Fatalf("challenge status %d", c)
	}
}
