package httpx

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestDecodeJSONRejectsUnknownFieldsAndTrailingData(t *testing.T) {
	type payload struct {
		A string `json:"a"`
	}
	cases := map[string]bool{
		`{"a":"x"}`:           true,
		`{"a":"x","b":1}`:     false,
		`{"a":"x"} {"a":"y"}`: false,
		`not json`:            false,
	}
	for body, ok := range cases {
		r := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(body))
		var p payload
		err := DecodeJSON(httptest.NewRecorder(), r, &p)
		if (err == nil) != ok {
			t.Errorf("body %q: err=%v, want ok=%v", body, err, ok)
		}
	}
}

func TestDecodeJSONEnforcesSizeLimit(t *testing.T) {
	big := `{"a":"` + strings.Repeat("x", MaxBodyBytes) + `"}`
	r := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(big))
	var p struct{ A string }
	if err := DecodeJSON(httptest.NewRecorder(), r, &p); err == nil {
		t.Fatal("expected oversized body to be rejected")
	}
}

func TestRateLimiterBlocksAfterBurst(t *testing.T) {
	rl := NewRateLimiter(3)
	h := rl.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	codes := make([]int, 0, 4)
	for range 4 {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/", nil)
		req.RemoteAddr = "203.0.113.7:5555"
		h.ServeHTTP(rec, req)
		codes = append(codes, rec.Code)
	}
	if codes[2] != http.StatusNoContent || codes[3] != http.StatusTooManyRequests {
		t.Fatalf("codes = %v", codes)
	}

	// A different client is unaffected.
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/", nil)
	req.RemoteAddr = "198.51.100.1:1"
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("other client got %d", rec.Code)
	}
}

func TestKeyedLimiterRefillsOverPeriodAndIsPerKey(t *testing.T) {
	now := time.Unix(0, 0)
	l := NewKeyedLimiter(6, time.Hour)
	l.now = func() time.Time { return now }
	for i := range 6 {
		if !l.Allow("a") {
			t.Fatalf("request %d refused within burst", i+1)
		}
	}
	if l.Allow("a") {
		t.Fatal("7th request in the hour allowed")
	}
	if !l.Allow("b") {
		t.Fatal("another key was limited")
	}
	now = now.Add(10 * time.Minute) // one token refills every hour/6
	if !l.Allow("a") || l.Allow("a") {
		t.Fatal("expected exactly one refilled token after 10 minutes")
	}
}

func TestKeyedLimiterEvictionDoesNotResetLimitEarly(t *testing.T) {
	now := time.Unix(0, 0)
	l := NewKeyedLimiter(1, time.Hour)
	l.now = func() time.Time { return now }
	l.Allow("victim")
	// Fill the map past the cleanup threshold 30 minutes later; the
	// exhausted entry is idle but its bucket has not refilled yet.
	now = now.Add(30 * time.Minute)
	for i := range 10_001 {
		l.Allow("k" + strconv.Itoa(i))
	}
	if l.Allow("victim") {
		t.Fatal("eviction reset an exhausted bucket before it refilled")
	}
}

func TestRequestLoggerSupportsHijack(t *testing.T) {
	var hijackable bool
	h := RequestLogger(slog.New(slog.NewTextHandler(io.Discard, nil)))(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, hijackable = w.(http.Hijacker)
	}))
	srv := httptest.NewServer(h)
	defer srv.Close()
	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if !hijackable {
		t.Fatal("logger hides http.Hijacker; WebSocket upgrades would fail")
	}
}
