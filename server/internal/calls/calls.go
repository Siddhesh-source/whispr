// Package calls hands out short-lived TURN credentials for voice and video
// calls. Call signaling itself is ordinary end-to-end encrypted envelopes;
// the server never sees offers, answers or candidates. Media relayed by
// TURN is DTLS-SRTP, so the relay sees only ciphertext.
//
// Credentials follow coturn's REST-API scheme (use-auth-secret): the
// username is "<expiry unix time>:<user id>" and the password is
// base64(HMAC-SHA1(secret, username)). coturn checks both without any call
// to this server.
package calls

import (
	"context"
	"crypto/hmac"
	"crypto/sha1" //nolint:gosec // HMAC-SHA1 is what coturn's REST-API scheme specifies.
	"encoding/base64"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"whispr/server/internal/platform/httpx"
)

// Options configures the relay. An empty Secret or URLs disables it.
type Options struct {
	Secret string
	URLs   []string
	TTL    time.Duration
	// PerMinute bounds credential requests per user.
	PerMinute int
}

type Module struct {
	opts    Options
	userID  func(context.Context) (uuid.UUID, bool)
	limiter *httpx.KeyedLimiter
	now     func() time.Time
}

func NewModule(opts Options, userID func(context.Context) (uuid.UUID, bool)) *Module {
	if opts.TTL <= 0 {
		opts.TTL = 10 * time.Minute
	}
	if opts.PerMinute <= 0 {
		opts.PerMinute = 10
	}
	return &Module{
		opts:    opts,
		userID:  userID,
		limiter: httpx.NewKeyedLimiter(opts.PerMinute, time.Minute),
		now:     time.Now,
	}
}

// Enabled reports whether a relay is configured.
func (m *Module) Enabled() bool { return m.opts.Secret != "" && len(m.opts.URLs) > 0 }

type turnResponse struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
	TTL        int64    `json:"ttl"`
}

// Routes mounts authenticated call endpoints.
func (m *Module) Routes(r chi.Router) {
	r.Get("/calls/turn", m.turn)
}

func (m *Module) turn(w http.ResponseWriter, r *http.Request) {
	if !m.Enabled() {
		httpx.WriteError(w, http.StatusServiceUnavailable, "calls_unavailable", "no call relay is configured")
		return
	}
	id, ok := m.userID(r.Context())
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "sign in first")
		return
	}
	if !m.limiter.Allow(id.String()) {
		httpx.WriteError(w, http.StatusTooManyRequests, "rate_limited", "too many requests")
		return
	}
	user, password := Credentials(m.opts.Secret, id, m.now().Add(m.opts.TTL))
	httpx.WriteJSON(w, http.StatusOK, turnResponse{
		URLs:       m.opts.URLs,
		Username:   user,
		Credential: password,
		TTL:        int64(m.opts.TTL / time.Second),
	})
}

// Credentials returns the coturn REST-API username and password for user,
// valid until expiry.
func Credentials(secret string, user uuid.UUID, expiry time.Time) (username, password string) {
	username = strconv.FormatInt(expiry.Unix(), 10) + ":" + user.String()
	mac := hmac.New(sha1.New, []byte(secret))
	mac.Write([]byte(username))
	return username, base64.StdEncoding.EncodeToString(mac.Sum(nil))
}
