package auth

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/go-chi/chi/v5"

	"whispr/server/internal/sigverify/sigverifytest"
)

func newTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	svc, _ := newTestService(t)
	h := NewHandler(svc, slog.New(slog.NewTextHandler(io.Discard, nil)))
	r := chi.NewRouter()
	r.Route("/v1", func(r chi.Router) {
		h.PublicRoutes(r)
		r.Group(func(r chi.Router) {
			r.Use(RequireAuth(svc))
			h.AuthedRoutes(r)
		})
	})
	srv := httptest.NewServer(r)
	t.Cleanup(srv.Close)
	return srv
}

func postJSON(t *testing.T, url string, body any, out any) int {
	t.Helper()
	b, _ := json.Marshal(body)
	resp, err := http.Post(url, "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil && resp.StatusCode < 300 {
		if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
			t.Fatal(err)
		}
	}
	return resp.StatusCode
}

func TestHTTPFullFlow(t *testing.T) {
	srv := newTestServer(t)
	key := sigverifytest.NewPublicKey()

	var reg registerResponse
	code := postJSON(t, srv.URL+"/v1/register", registerRequest{
		IdentityKey: key, DisplayName: "Ada", Signature: sigverifytest.Sign(key, RegisterMessage(key, "Ada")),
	}, &reg)
	if code != http.StatusCreated {
		t.Fatalf("register status %d", code)
	}

	var ch challengeResponse
	if code := postJSON(t, srv.URL+"/v1/auth/challenge", challengeRequest{UserID: reg.UserID}, &ch); code != http.StatusOK {
		t.Fatalf("challenge status %d", code)
	}
	if len(ch.Nonce) != NonceLen {
		t.Fatalf("nonce length %d", len(ch.Nonce))
	}

	var vr verifyResponse
	code = postJSON(t, srv.URL+"/v1/auth/verify", verifyRequest{
		ChallengeID: ch.ChallengeID, Signature: sigverifytest.Sign(key, AuthMessage(reg.UserID, ch.Nonce)),
	}, &vr)
	if code != http.StatusOK || vr.Token == "" {
		t.Fatalf("verify status %d", code)
	}

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/v1/me", nil)
	req.Header.Set("Authorization", "Bearer "+vr.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var me meResponse
	_ = json.NewDecoder(resp.Body).Decode(&me)
	if resp.StatusCode != http.StatusOK || me.UserID != reg.UserID || me.DisplayName != "Ada" {
		t.Fatalf("me: %d %+v", resp.StatusCode, me)
	}
	if cc := resp.Header.Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("Cache-Control = %q", cc)
	}
}

func TestHTTPMeRequiresToken(t *testing.T) {
	srv := newTestServer(t)
	for _, header := range []string{"", "Bearer ", "Bearer nope", "Basic abc"} {
		req, _ := http.NewRequest(http.MethodGet, srv.URL+"/v1/me", nil)
		if header != "" {
			req.Header.Set("Authorization", header)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("header %q: status %d", header, resp.StatusCode)
		}
	}
}

func TestHTTPBadSignatureAndBadInputStatuses(t *testing.T) {
	srv := newTestServer(t)
	key := sigverifytest.NewPublicKey()
	if code := postJSON(t, srv.URL+"/v1/register", registerRequest{IdentityKey: key, DisplayName: "Ada", Signature: []byte("x")}, nil); code != http.StatusUnauthorized {
		t.Errorf("bad signature: %d", code)
	}
	if code := postJSON(t, srv.URL+"/v1/register", registerRequest{IdentityKey: key, DisplayName: " ", Signature: []byte("x")}, nil); code != http.StatusBadRequest {
		t.Errorf("bad name: %d", code)
	}
	if code := postJSON(t, srv.URL+"/v1/register", map[string]any{"extra": 1}, nil); code != http.StatusBadRequest {
		t.Errorf("unknown field: %d", code)
	}
}
