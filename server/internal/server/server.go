// Package server assembles modules into the HTTP router.
package server

import (
	"log/slog"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

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
	Messaging   *messaging.Module
	Contacts    *contacts.Module
	Keys        *keys.Module
	Push        *push.Module
	Profile     *profile.Module
}

func NewRouter(d Deps) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	r.Use(httpx.RequestLogger(d.Log))

	r.Get("/healthz", health.Handler(d.DB))

	authHandler := auth.NewHandler(d.Auth, d.Log)
	r.Route("/v1", func(r chi.Router) {
		r.Group(func(r chi.Router) {
			r.Use(d.RateLimiter.Middleware)
			authHandler.PublicRoutes(r)
		})
		r.Group(func(r chi.Router) {
			r.Use(auth.RequireAuth(d.Auth))
			authHandler.AuthedRoutes(r)
			d.Messaging.Routes(r)
			d.Contacts.Routes(r)
			d.Keys.Routes(r)
			d.Push.Routes(r)
			d.Profile.Routes(r)
		})
	})
	return r
}
