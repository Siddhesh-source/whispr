// Package httpx holds shared HTTP helpers: JSON encoding, error responses,
// and middleware that is safe to run in front of every route.
package httpx

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
	"golang.org/x/time/rate"
)

// MaxBodyBytes bounds every JSON request body.
const MaxBodyBytes = 16 << 10

// Error is the JSON shape of every error response. Messages are fixed strings
// chosen by handlers and never echo client input.
type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func WriteError(w http.ResponseWriter, status int, code, msg string) {
	WriteJSON(w, status, Error{Code: code, Message: msg})
}

// DecodeJSON decodes a single bounded JSON object, rejecting unknown fields.
func DecodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	return DecodeJSONLimit(w, r, dst, MaxBodyBytes)
}

// DecodeJSONLimit is DecodeJSON with a caller-chosen body limit, for the few
// endpoints whose payloads are legitimately larger (prekey uploads).
func DecodeJSONLimit(w http.ResponseWriter, r *http.Request, dst any, limit int64) error {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return err
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("trailing data after JSON object")
	}
	return nil
}

// RequestLogger logs method, route pattern, status, and latency. It never
// logs headers, query strings, bodies, or client IPs.
func RequestLogger(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(sw, r)
			route := "unmatched"
			if rc := chi.RouteContext(r.Context()); rc != nil && rc.RoutePattern() != "" {
				route = rc.RoutePattern()
			}
			log.Info("http",
				"method", r.Method,
				"route", route,
				"status", sw.status,
				"duration_ms", time.Since(start).Milliseconds(),
			)
		})
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (s *statusWriter) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

// Hijack lets WebSocket upgrades take over the connection through the logger.
func (s *statusWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	hj, ok := s.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, errors.New("httpx: underlying ResponseWriter cannot hijack")
	}
	s.status = http.StatusSwitchingProtocols
	return hj.Hijack()
}

// Unwrap exposes the underlying writer to http.ResponseController.
func (s *statusWriter) Unwrap() http.ResponseWriter { return s.ResponseWriter }

// KeyedLimiter is an in-memory token bucket per key: each key may make
// `burst` requests, refilled evenly over `per`. State is per process.
type KeyedLimiter struct {
	mu      sync.Mutex
	burst   int
	every   time.Duration
	idle    time.Duration
	clients map[string]*client
	now     func() time.Time
}

type client struct {
	lim      *rate.Limiter
	lastSeen time.Time
}

// NewKeyedLimiter allows burst requests per key every per.
func NewKeyedLimiter(burst int, per time.Duration) *KeyedLimiter {
	// An entry may only be forgotten once its bucket would have refilled,
	// or eviction would reset the limit early.
	idle := max(10*time.Minute, per)
	return &KeyedLimiter{
		burst: burst, every: per / time.Duration(burst), idle: idle,
		clients: map[string]*client{}, now: time.Now,
	}
}

// Allow reports whether key may make one more request now.
func (l *KeyedLimiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	c, ok := l.clients[key]
	if !ok {
		c = &client{lim: rate.NewLimiter(rate.Every(l.every), l.burst)}
		l.clients[key] = c
	}
	c.lastSeen = now
	// Opportunistic cleanup keeps memory bounded without a goroutine.
	if len(l.clients) > 10_000 {
		for k, v := range l.clients {
			if now.Sub(v.lastSeen) > l.idle {
				delete(l.clients, k)
			}
		}
	}
	return c.lim.AllowN(now, 1)
}

// RateLimiter is a per-client-IP token bucket. The client IP is taken from
// the TCP peer address only; X-Forwarded-For is not trusted.
type RateLimiter struct {
	keyed *KeyedLimiter
}

func NewRateLimiter(perMinute int) *RateLimiter {
	return &RateLimiter{keyed: NewKeyedLimiter(perMinute, time.Minute)}
}

// WriteRateLimited is the 429 response every limiter uses.
func WriteRateLimited(w http.ResponseWriter, retryAfter time.Duration) {
	w.Header().Set("Retry-After", strconv.Itoa(max(1, int(retryAfter.Seconds()))))
	WriteError(w, http.StatusTooManyRequests, "rate_limited", "too many requests")
}

func (rl *RateLimiter) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			ip = r.RemoteAddr
		}
		if !rl.keyed.Allow(ip) {
			WriteRateLimited(w, time.Minute)
			return
		}
		next.ServeHTTP(w, r)
	})
}
