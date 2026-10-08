// Package server assembles modules into the HTTP router.
package server

import (
	"log/slog"
	"net/http"
	"net/netip"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/google/uuid"

	"whispr/server/internal/attachments"
	"whispr/server/internal/auth"
	"whispr/server/internal/contacts"
	"whispr/server/internal/health"
	"whispr/server/internal/keys"
	"whispr/server/internal/messaging"
	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/profile"
	"whispr/server/internal/push"
)

type Deps struct {
	Log         *slog.Logger
	DB          health.Pinger
	Auth        *auth.Service
	RateLimiter *httpx.RateLimiter
	// RegisterLimiter is an extra per-IP limit on account creation (nil: none).
	RegisterLimiter *httpx.RateLimiter
	// UserLimiter bounds every authenticated route per user (nil: none).
	UserLimiter *httpx.UserLimiter
	// TrustedProxies may set X-Forwarded-For (the TLS terminator).
	TrustedProxies []netip.Prefix
	// OnSignOut closes a user's live connections after logout or deletion.
	OnSignOut func(uuid.UUID)
	Messaging *messaging.Module
	Contacts  *contacts.Module
	Keys      *keys.Module
	Push      *push.Module
	Profile   *profile.Module
	// Attachments is nil when object storage is not configured; uploads then
	// answer 503 so clients show "media unavailable" instead of failing silently.
	Attachments *attachments.Module
}

func NewRouter(d Deps) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	r.Use(httpx.SecurityHeaders)
	r.Use(httpx.ClientIP(d.TrustedProxies))
	r.Use(httpx.RequestLogger(d.Log))

	r.Get("/healthz", health.Handler(d.DB))

	authHandler := auth.NewHandler(d.Auth, d.Log, d.OnSignOut)
	r.Route("/v1", func(r chi.Router) {
		r.Group(func(r chi.Router) {
			r.Use(d.RateLimiter.Middleware)
			var registerLimit func(http.Handler) http.Handler
			if d.RegisterLimiter != nil {
				registerLimit = d.RegisterLimiter.Middleware
			}
			authHandler.PublicRoutes(r, registerLimit)
		})
		r.Group(func(r chi.Router) {
			r.Use(auth.RequireAuth(d.Auth))
			if d.UserLimiter != nil {
				r.Use(d.UserLimiter.Middleware)
			}
			authHandler.AuthedRoutes(r)
			d.Messaging.Routes(r)
			d.Contacts.Routes(r)
			d.Keys.Routes(r)
			d.Push.Routes(r)
			d.Profile.Routes(r)
			if d.Attachments != nil {
				d.Attachments.Routes(r)
			} else {
				r.HandleFunc("/attachments*", func(w http.ResponseWriter, _ *http.Request) {
					httpx.WriteError(w, http.StatusServiceUnavailable, "media_unavailable", "media storage is not configured")
				})
			}
		})
	})
	return r
}
