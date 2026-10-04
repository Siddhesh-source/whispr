package profile_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"whispr/server/internal/auth"
	"whispr/server/internal/platform/dbtest"
	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/profile"
	"whispr/server/internal/sigverify/sigverifytest"
)

func setup(t *testing.T, lookupLimit int) (*httptest.Server, *auth.Service, *pgxpool.Pool) {
	t.Helper()
	pool := dbtest.New(t)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	svc := auth.NewService(auth.NewPGStore(pool), sigverifytest.Fake{}, auth.Options{TokenTTL: time.Hour, ChallengeTTL: time.Minute})
	mod := profile.NewModule(profile.NewStore(pool), log, auth.UserIDFrom, httpx.NewRateLimiter(lookupLimit).Middleware)
	r := chi.NewRouter()
	r.Route("/v1", func(r chi.Router) {
		r.Use(auth.RequireAuth(svc))
		mod.Routes(r)
	})
	srv := httptest.NewServer(r)
	t.Cleanup(srv.Close)
	return srv, svc, pool
}

func newUser(t *testing.T, svc *auth.Service, name string) (uuid.UUID, string) {
	t.Helper()
	ctx := context.Background()
	key := sigverifytest.NewPublicKey()
	u, _, err := svc.Register(ctx, key, name, sigverifytest.Sign(key, auth.RegisterMessage(key, name)))
	if err != nil {
		t.Fatal(err)
	}
	c, _ := svc.Challenge(ctx, u.ID)
	tok, _, err := svc.Verify(ctx, c.ID, sigverifytest.Sign(key, auth.AuthMessage(u.ID, c.Nonce)))
	if err != nil {
		t.Fatal(err)
	}
	return u.ID, tok
}

func call(t *testing.T, srv *httptest.Server, token, method, path string, body any) (int, map[string]any) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, srv.URL+path, rd)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func TestUsernameClaimLookupAndRelease(t *testing.T) {
	srv, svc, _ := setup(t, 1000)
	alice, aliceTok := newUser(t, svc, "Alice")
	_, bobTok := newUser(t, svc, "Bob")

	code, body := call(t, srv, aliceTok, http.MethodPut, "/v1/me/username", map[string]string{"nickname": "Alice_W"})
	if code != 200 {
		t.Fatalf("claim: %d %v", code, body)
	}
	username := body["username"].(string)
	if !regexp.MustCompile(`^alice_w\.\d{2}$`).MatchString(username) {
		t.Fatalf("username %q", username)
	}

	// Lookup is exact and case-insensitive on the full handle.
	code, body = call(t, srv, bobTok, http.MethodGet, "/v1/usernames/"+username, nil)
	if code != 200 || body["user_id"] != alice.String() || body["identity_key"] == nil {
		t.Fatalf("lookup: %d %v", code, body)
	}
	if code, _ = call(t, srv, bobTok, http.MethodGet, "/v1/usernames/ALICE_W."+username[len(username)-2:], nil); code != 200 {
		t.Fatalf("case-insensitive lookup: %d", code)
	}
	// The nickname alone is not enough.
	if code, _ = call(t, srv, bobTok, http.MethodGet, "/v1/usernames/alice_w", nil); code != http.StatusBadRequest {
		t.Fatalf("nickname-only lookup: %d", code)
	}

	if code, _ = call(t, srv, aliceTok, http.MethodDelete, "/v1/me/username", nil); code != http.StatusNoContent {
		t.Fatalf("release: %d", code)
	}
	if code, _ = call(t, srv, bobTok, http.MethodGet, "/v1/usernames/"+username, nil); code != http.StatusNotFound {
		t.Fatalf("lookup after release: %d", code)
	}
}

func TestNicknameValidation(t *testing.T) {
	srv, svc, _ := setup(t, 1000)
	_, tok := newUser(t, svc, "Alice")
	for _, nick := range []string{"", "ab", "1abc", "a b c", "abc.12", "ab-cd", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ålice"} {
		if code, _ := call(t, srv, tok, http.MethodPut, "/v1/me/username", map[string]string{"nickname": nick}); code != http.StatusBadRequest {
			t.Errorf("nickname %q accepted (%d)", nick, code)
		}
	}
}

func TestManyUsersShareANicknameWithDistinctNumbers(t *testing.T) {
	srv, svc, _ := setup(t, 1000)
	seen := map[string]bool{}
	for range 20 {
		_, tok := newUser(t, svc, "Sam")
		code, body := call(t, srv, tok, http.MethodPut, "/v1/me/username", map[string]string{"nickname": "sam"})
		if code != 200 {
			t.Fatalf("claim: %d %v", code, body)
		}
		u := body["username"].(string)
		if seen[u] {
			t.Fatalf("duplicate username %s", u)
		}
		seen[u] = true
	}
}

func TestDisplayNameUpdate(t *testing.T) {
	srv, svc, _ := setup(t, 1000)
	_, tok := newUser(t, svc, "Alice")
	if code, _ := call(t, srv, tok, http.MethodPut, "/v1/me/profile", map[string]string{"display_name": "Alice W."}); code != http.StatusNoContent {
		t.Fatalf("update: %d", code)
	}
	_, body := call(t, srv, tok, http.MethodGet, "/v1/me/profile", nil)
	if body["display_name"] != "Alice W." {
		t.Fatalf("profile %v", body)
	}
	if code, _ := call(t, srv, tok, http.MethodPut, "/v1/me/profile", map[string]string{"display_name": "evil\u202egnp"}); code != http.StatusBadRequest {
		t.Fatalf("bidi name accepted: %d", code)
	}
}

func TestUsernameLookupIsRateLimited(t *testing.T) {
	srv, svc, _ := setup(t, 3)
	_, tok := newUser(t, svc, "Alice")
	var last int
	for range 5 {
		last, _ = call(t, srv, tok, http.MethodGet, "/v1/usernames/nobody.11", nil)
	}
	if last != http.StatusTooManyRequests {
		t.Fatalf("lookup not rate limited: %d", last)
	}
}
