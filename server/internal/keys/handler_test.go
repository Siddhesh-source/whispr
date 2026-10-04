package keys

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"whispr/server/internal/platform/dbtest"
	"whispr/server/internal/platform/httpx"
)

type userKey struct{}

// testServer authenticates requests by an X-User header so the tests can
// act as any user; production uses auth.RequireAuth.
func testServer(t *testing.T, limits Limits) (*httptest.Server, *PGStore) {
	t.Helper()
	store := NewPGStore(dbtest.New(t))
	m := NewModule(store, fakeVerifier{}, slog.New(slog.NewTextHandler(io.Discard, nil)),
		func(ctx context.Context) (uuid.UUID, bool) {
			id, ok := ctx.Value(userKey{}).(uuid.UUID)
			return id, ok
		}, limits)
	r := chi.NewRouter()
	r.Use(func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			id, _ := uuid.Parse(req.Header.Get("X-User"))
			next.ServeHTTP(w, req.WithContext(context.WithValue(req.Context(), userKey{}, id)))
		})
	})
	r.Route("/v1", m.Routes)
	srv := httptest.NewServer(r)
	t.Cleanup(srv.Close)
	return srv, store
}

func do(t *testing.T, srv *httptest.Server, as uuid.UUID, method, path string, body any) (int, map[string]any) {
	t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	default:
		raw, _ := json.Marshal(b)
		rd = bytes.NewReader(raw)
	}
	req, _ := http.NewRequest(method, srv.URL+path, rd)
	req.Header.Set("X-User", as.String())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func uploadJSON(u Upload) uploadRequest {
	r := uploadRequest{RegistrationID: u.RegistrationID}
	if u.SignedPreKey != nil {
		k := toJSON(*u.SignedPreKey)
		r.SignedPreKey = &k
	}
	if u.LastResort != nil {
		k := toJSON(*u.LastResort)
		r.LastResort = &k
	}
	for _, k := range u.OneTime {
		r.OneTime = append(r.OneTime, oneTimeKeyJSON(k))
	}
	for _, k := range u.Kyber {
		r.Kyber = append(r.Kyber, toJSON(k))
	}
	return r
}

func TestUploadCountAndBundleOverHTTP(t *testing.T) {
	srv, store := testServer(t, DefaultLimits())
	alice, aliceKey := newUser(t, store.pool)
	bob, _ := newUser(t, store.pool)

	// A full batch of 100 Kyber keys is far above the default 16 KiB body
	// limit and must still be accepted.
	if code, _ := do(t, srv, alice, http.MethodPut, "/v1/keys", uploadJSON(fullUpload(aliceKey, 1, MaxBatch))); code != http.StatusNoContent {
		t.Fatalf("upload status %d", code)
	}
	code, counts := do(t, srv, alice, http.MethodGet, "/v1/keys/count", nil)
	if code != http.StatusOK || counts["one_time_prekeys"] != float64(MaxBatch) || counts["kyber_prekeys"] != float64(MaxBatch) {
		t.Fatalf("count %d %v", code, counts)
	}

	code, b := do(t, srv, bob, http.MethodGet, "/v1/keys/"+alice.String(), nil)
	if code != http.StatusOK {
		t.Fatalf("bundle status %d", code)
	}
	if b["device_id"] != float64(1) || b["registration_id"] != float64(1234) || b["one_time_prekey"] == nil {
		t.Fatalf("bundle = %v", b)
	}
	if ky := b["kyber_prekey"].(map[string]any); ky["last_resort"] != false || ky["signature"] == nil {
		t.Fatalf("kyber prekey = %v", ky)
	}
}

func TestUploadErrors(t *testing.T) {
	srv, store := testServer(t, DefaultLimits())
	alice, aliceKey := newUser(t, store.pool)

	bad := fullUpload(aliceKey, 1, 1)
	bad.SignedPreKey.Signature[0] ^= 1
	if code, body := do(t, srv, alice, http.MethodPut, "/v1/keys", uploadJSON(bad)); code != http.StatusBadRequest || body["code"] != "invalid_keys" {
		t.Fatalf("bad signature: %d %v", code, body)
	}
	if code, _ := do(t, srv, alice, http.MethodPut, "/v1/keys", `{"unknown":1}`); code != http.StatusBadRequest {
		t.Fatalf("unknown field: %d", code)
	}
	if code, _ := do(t, srv, alice, http.MethodPut, "/v1/keys", `{"one_time_prekeys":[`+strings.Repeat(" ", MaxUploadBytes)+`]}`); code != http.StatusBadRequest {
		t.Fatalf("oversized body: %d", code)
	}

	if code, _ := do(t, srv, alice, http.MethodPut, "/v1/keys", uploadJSON(fullUpload(aliceKey, 1, 1))); code != http.StatusNoContent {
		t.Fatal("valid upload refused")
	}
	sp := signed(aliceKey, 1, ecKey()) // same ID, different key
	if code, body := do(t, srv, alice, http.MethodPut, "/v1/keys", uploadJSON(Upload{SignedPreKey: &sp})); code != http.StatusConflict || body["code"] != "key_id_conflict" {
		t.Fatalf("conflict: %d %v", code, body)
	}
}

func TestBundleErrors(t *testing.T) {
	srv, store := testServer(t, DefaultLimits())
	alice, _ := newUser(t, store.pool)
	bob, _ := newUser(t, store.pool)
	for _, c := range []struct {
		path, code string
		status     int
	}{
		{"/v1/keys/" + alice.String(), "self", http.StatusBadRequest},
		{"/v1/keys/not-a-uuid", "bad_request", http.StatusBadRequest},
		{"/v1/keys/" + uuid.NewString(), "unknown_user", http.StatusNotFound},
		{"/v1/keys/" + bob.String(), "no_keys", http.StatusNotFound},
	} {
		if status, body := do(t, srv, alice, http.MethodGet, c.path, nil); status != c.status || body["code"] != c.code {
			t.Errorf("%s: %d %v, want %d %s", c.path, status, body, c.status, c.code)
		}
	}
}

func TestBundleFetchesAreRateLimitedPerUserAndPerPair(t *testing.T) {
	srv, store := testServer(t, Limits{
		PerUser: httpx.NewKeyedLimiter(4, time.Minute),
		PerPair: httpx.NewKeyedLimiter(2, time.Hour),
	})
	alice, _ := newUser(t, store.pool)
	targets := make([]uuid.UUID, 4)
	for i := range targets {
		var key []byte
		targets[i], key = newUser(t, store.pool)
		if err := store.Upload(context.Background(), targets[i], fullUpload(key, 1, 5), now); err != nil {
			t.Fatal(err)
		}
	}
	fetch := func(target uuid.UUID) int {
		code, _ := do(t, srv, alice, http.MethodGet, "/v1/keys/"+target.String(), nil)
		return code
	}

	// Per pair: the third fetch of the same person within the hour is refused.
	got := []int{fetch(targets[0]), fetch(targets[0]), fetch(targets[0])}
	if got[0] != http.StatusOK || got[1] != http.StatusOK || got[2] != http.StatusTooManyRequests {
		t.Fatalf("per-pair limit not enforced: %v", got)
	}
	// Per user: 4 a minute in total. The refused fetch above still counted
	// against the per-user bucket, so one more is allowed, then nothing.
	if fetch(targets[1]) != http.StatusOK {
		t.Fatal("other target refused before the per-user limit")
	}
	if fetch(targets[2]) != http.StatusTooManyRequests {
		t.Fatal("per-user limit not enforced")
	}
	// The draining attempt left the target's other keys untouched.
	c, _ := store.Counts(context.Background(), targets[0])
	if c.OneTime != 3 {
		t.Fatalf("target 0 has %d one-time keys left, want 3", c.OneTime)
	}
}
