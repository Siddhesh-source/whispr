package httpx

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
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
