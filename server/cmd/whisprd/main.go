// Command whisprd is the Whispr backend.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"whispr/server/internal/auth"
	"whispr/server/internal/config"
	"whispr/server/internal/contacts"
	"whispr/server/internal/messaging"
	"whispr/server/internal/platform/db"
	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/platform/logging"
	"whispr/server/internal/server"
	"whispr/server/internal/sigverify"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}
	if err := run(); err != nil {
		slog.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	log := logging.New(os.Stdout, cfg.LogLevel)

	verifier, err := sigverify.New()
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := db.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	if err := db.Migrate(pool); err != nil {
		return err
	}

	authSvc := auth.NewService(auth.NewPGStore(pool), verifier, auth.Options{
		TokenTTL:     cfg.TokenTTL,
		ChallengeTTL: cfg.ChallengeTTL,
	})
	go authSvc.RunJanitor(ctx, time.Minute, log)

	srv := &http.Server{
		Addr: cfg.ListenAddr,
		Handler: server.NewRouter(server.Deps{
			Log:         log,
			DB:          pool,
			Auth:        authSvc,
			RateLimiter: httpx.NewRateLimiter(cfg.RateLimitPerMinute),
			Messaging:   messaging.New(),
			Contacts:    contacts.New(),
		}),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", cfg.ListenAddr)
		if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	log.Info("shutting down")
	return srv.Shutdown(shutdownCtx)
}

// healthcheck probes the local /healthz endpoint. The runtime image is
// distroless (no shell or curl), so the binary checks itself.
func healthcheck() int {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	if addr[0] == ':' {
		addr = "127.0.0.1" + addr
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+addr+"/healthz", nil)
	if err != nil {
		return 1
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
