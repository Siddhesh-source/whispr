package attachments_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"whispr/server/internal/attachments"
	"whispr/server/internal/platform/dbtest"
	"whispr/server/internal/platform/httpx"
)

type ctxKey struct{}

// harness mounts the module behind a fake auth middleware that takes the
// user ID from the X-User header.
type harness struct {
	t       *testing.T
	srv     *httptest.Server
	mod     *attachments.Module
	objects attachments.Objects
	now     time.Time
	users   []uuid.UUID
}

func newHarness(t *testing.T, objects attachments.Objects, opts attachments.Options) *harness {
	t.Helper()
	pool := dbtest.New(t)
	h := &harness{t: t, objects: objects, now: time.Now().UTC()}
	if opts.Now == nil {
		opts.Now = func() time.Time { return h.now }
	}
	for range 2 {
		id := uuid.New()
		key := make([]byte, 33)
		_, _ = rand.Read(key)
		if _, err := pool.Exec(context.Background(),
			`INSERT INTO users (id, identity_key, display_name, created_at) VALUES ($1, $2, 'u', now())`, id, key); err != nil {
			t.Fatal(err)
		}
		h.users = append(h.users, id)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	h.mod = attachments.NewModule(attachments.NewPGStore(pool), objects, log,
		func(ctx context.Context) (uuid.UUID, bool) {
			id, ok := ctx.Value(ctxKey{}).(uuid.UUID)
			return id, ok
		}, opts)
	r := chi.NewRouter()
	r.Use(func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			id, err := uuid.Parse(r.Header.Get("X-User"))
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
		})
	})
	r.Route("/v1", h.mod.Routes)
	h.srv = httptest.NewServer(r)
	t.Cleanup(h.srv.Close)
	return h
}

func (h *harness) upload(user uuid.UUID, body []byte) (*http.Response, uuid.UUID) {
	h.t.Helper()
	req, _ := http.NewRequest(http.MethodPost, h.srv.URL+"/v1/attachments", bytes.NewReader(body))
	req.Header.Set("X-User", user.String())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	var out struct {
		ID uuid.UUID `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp, out.ID
}

func (h *harness) download(user uuid.UUID, id string) (int, []byte) {
	h.t.Helper()
	req, _ := http.NewRequest(http.MethodGet, h.srv.URL+"/v1/attachments/"+id, nil)
	req.Header.Set("X-User", user.String())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, b
}

func blob(n int) []byte {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return b
}

func TestUploadThenAnotherUserDownloadsTheSameBytes(t *testing.T) {
	h := newHarness(t, attachments.NewMemObjects(), attachments.Options{})
	data := blob(70_000)
	resp, id := h.upload(h.users[0], data)
	if resp.StatusCode != http.StatusCreated || id == uuid.Nil {
		t.Fatalf("upload: %d %v", resp.StatusCode, id)
	}
	if id.Version() != 4 {
		t.Fatalf("attachment IDs must be random (v4), got v%d", id.Version())
	}
	status, got := h.download(h.users[1], id.String())
	if status != http.StatusOK || !bytes.Equal(got, data) {
		t.Fatalf("download: %d, %d bytes", status, len(got))
	}
}

func TestUploadLimits(t *testing.T) {
	h := newHarness(t, attachments.NewMemObjects(), attachments.Options{})
	if resp, _ := h.upload(h.users[0], nil); resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("empty: %d", resp.StatusCode)
	}
	// Declared too large: refused before reading the body.
	// (Served in-process: an HTTP client won't send a body shorter than its length.)
	req := httptest.NewRequest(http.MethodPost, "/v1/attachments", strings.NewReader("x"))
	req.ContentLength = attachments.MaxBlobBytes + 1
	req.Header.Set("X-User", h.users[0].String())
	rec := httptest.NewRecorder()
	h.srv.Config.Handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("too large: %d", rec.Code)
	}
	// No Content-Length (chunked): refused.
	req, _ = http.NewRequest(http.MethodPost, h.srv.URL+"/v1/attachments", io.NopCloser(strings.NewReader("abc")))
	req.ContentLength = -1
	req.Header.Set("X-User", h.users[0].String())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusLengthRequired {
		t.Fatalf("chunked: %d", resp.StatusCode)
	}
}

func TestUnknownAndMalformedIDs(t *testing.T) {
	h := newHarness(t, attachments.NewMemObjects(), attachments.Options{})
	if s, _ := h.download(h.users[0], uuid.NewString()); s != http.StatusNotFound {
		t.Fatalf("unknown: %d", s)
	}
	if s, _ := h.download(h.users[0], "not-a-uuid"); s != http.StatusBadRequest {
		t.Fatalf("malformed: %d", s)
	}
}

func TestUploadsAreRateLimitedPerUser(t *testing.T) {
	h := newHarness(t, attachments.NewMemObjects(), attachments.Options{Uploads: httpx.NewKeyedLimiter(2, time.Hour)})
	for range 2 {
		if resp, _ := h.upload(h.users[0], blob(10)); resp.StatusCode != http.StatusCreated {
			t.Fatalf("upload: %d", resp.StatusCode)
		}
	}
	if resp, _ := h.upload(h.users[0], blob(10)); resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("third upload: %d", resp.StatusCode)
	}
	if resp, _ := h.upload(h.users[1], blob(10)); resp.StatusCode != http.StatusCreated {
		t.Fatalf("other user: %d", resp.StatusCode)
	}
}

func TestRetentionHidesThenPurgesExpiredBlobs(t *testing.T) {
	objects := attachments.NewMemObjects()
	h := newHarness(t, objects, attachments.Options{Retention: 24 * time.Hour})
	_, old := h.upload(h.users[0], blob(100))
	h.now = h.now.Add(23 * time.Hour)
	_, fresh := h.upload(h.users[0], blob(100))
	h.now = h.now.Add(2 * time.Hour) // old is 25 h, fresh is 2 h

	if s, _ := h.download(h.users[1], old.String()); s != http.StatusNotFound {
		t.Fatalf("expired blob still served: %d", s)
	}
	n, err := h.mod.Purge(context.Background())
	if err != nil || n != 1 {
		t.Fatalf("purge: %d, %v", n, err)
	}
	snap := objects.Snapshot()
	if _, ok := snap["attachments/"+old.String()]; ok {
		t.Fatal("expired object still in storage")
	}
	if _, ok := snap["attachments/"+fresh.String()]; !ok {
		t.Fatal("fresh object purged")
	}
	if s, _ := h.download(h.users[1], fresh.String()); s != http.StatusOK {
		t.Fatalf("fresh blob: %d", s)
	}
}

// Against real S3-compatible storage (MinIO). Set WHISPR_TEST_S3_ENDPOINT
// (host:port) plus WHISPR_TEST_S3_ACCESS_KEY / _SECRET_KEY / _BUCKET.
func TestS3RoundTripAndPurge(t *testing.T) {
	endpoint := os.Getenv("WHISPR_TEST_S3_ENDPOINT")
	if endpoint == "" {
		t.Skip("WHISPR_TEST_S3_ENDPOINT not set")
	}
	objects, err := attachments.NewS3Objects(attachments.S3Config{
		Endpoint: endpoint, AccessKey: os.Getenv("WHISPR_TEST_S3_ACCESS_KEY"),
		SecretKey: os.Getenv("WHISPR_TEST_S3_SECRET_KEY"), Bucket: os.Getenv("WHISPR_TEST_S3_BUCKET"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := objects.CheckBucket(context.Background()); err != nil {
		t.Fatal(err)
	}
	h := newHarness(t, objects, attachments.Options{Retention: time.Hour})
	data := blob(3 << 20)
	_, id := h.upload(h.users[0], data)
	if s, got := h.download(h.users[1], id.String()); s != http.StatusOK || !bytes.Equal(got, data) {
		t.Fatalf("download: %d, %d bytes", s, len(got))
	}
	h.now = h.now.Add(2 * time.Hour)
	if n, err := h.mod.Purge(context.Background()); err != nil || n != 1 {
		t.Fatalf("purge: %d, %v", n, err)
	}
	if _, _, err := objects.Get(context.Background(), "attachments/"+id.String()); !errors.Is(err, attachments.ErrNotFound) {
		t.Fatalf("object after purge: %v", err)
	}
}
