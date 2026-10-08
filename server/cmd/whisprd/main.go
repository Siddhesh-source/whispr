// Command whisprd is the Whispr backend.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"whispr/server/internal/attachments"
	"whispr/server/internal/auth"
	"whispr/server/internal/config"
	"whispr/server/internal/contacts"
	"whispr/server/internal/keys"
	"whispr/server/internal/messaging"
	"whispr/server/internal/platform/db"
	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/platform/logging"
	"whispr/server/internal/profile"
	"whispr/server/internal/push"
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

	authStore := auth.NewPGStore(pool)
	authSvc := auth.NewService(authStore, verifier, auth.Options{
		TokenTTL:     cfg.TokenTTL,
		ChallengeTTL: cfg.ChallengeTTL,
	})
	go authSvc.RunJanitor(ctx, time.Minute, log)

	pushStore := push.NewPGStore(pool)
	var waker messaging.Waker = messaging.NoopWaker{}
	if cfg.FCMCredentialsFile != "" {
		fcm, err := push.NewFCMWakerFromFile(ctx, pushStore, cfg.FCMCredentialsFile)
		if err != nil {
			return err
		}
		waker = fcm
		log.Info("push enabled", "provider", "fcm")
	} else {
		log.Warn("push disabled: FCM_CREDENTIALS_FILE not set")
	}

	hub := messaging.NewHub()
	msgSvc := messaging.NewService(messaging.NewPGStore(pool), hub, waker, log, messaging.Options{})
	go msgSvc.RunJanitor(ctx, time.Hour)
	gateway := messaging.NewGateway(msgSvc, hub, log, messaging.GatewayOptions{
		UserID: auth.UserIDFrom, TokenExpiry: auth.TokenExpiryFrom,
	})

	var media *attachments.Module
	if cfg.S3Endpoint != "" {
		objects, err := attachments.NewS3Objects(attachments.S3Config{
			Endpoint: cfg.S3Endpoint, AccessKey: cfg.S3AccessKey, SecretKey: cfg.S3SecretKey,
			Bucket: cfg.S3Bucket, UseSSL: cfg.S3UseSSL,
		})
		if err != nil {
			return err
		}
		if err := objects.CheckBucket(ctx); err != nil {
			return err
		}
		media = attachments.NewModule(attachments.NewPGStore(pool), objects, log, auth.UserIDFrom,
			attachments.Options{Retention: cfg.AttachmentRetention})
		go media.RunJanitor(ctx, time.Hour)
		log.Info("media enabled", "retention", cfg.AttachmentRetention.String())
	} else {
		log.Warn("media disabled: S3_ENDPOINT not set")
	}

	srv := &http.Server{
		Addr: cfg.ListenAddr,
		Handler: server.NewRouter(server.Deps{
			Log:             log,
			DB:              pool,
			Auth:            authSvc,
			RateLimiter:     httpx.NewRateLimiter(cfg.RateLimitPerMinute),
			RegisterLimiter: httpx.NewRateLimiterPer(cfg.RegistrationsPerHour, time.Hour),
			UserLimiter: httpx.NewUserLimiter(cfg.UserRequestsPerMinute, time.Minute, func(r *http.Request) (string, bool) {
				id, ok := auth.UserIDFrom(r.Context())
				return id.String(), ok
			}),
			TrustedProxies: cfg.TrustedProxies,
			OnSignOut:      hub.Disconnect,
			Messaging:      messaging.New(gateway),
			Contacts:       contacts.New(authStore, log),
			Keys:           keys.NewModule(keys.NewPGStore(pool), verifier, log, auth.UserIDFrom, keys.DefaultLimits()),
			Push:           push.NewModule(pushStore, log, auth.UserIDFrom),
			// Username lookups get a stricter per-IP limit than other calls.
			Profile:     profile.NewModule(profile.NewStore(pool), log, auth.UserIDFrom, httpx.NewRateLimiter(10).Middleware),
			Attachments: media,
		}),
		ReadHeaderTimeout: 5 * time.Second,
		MaxHeaderBytes:    16 << 10,
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
// distroless (no shell or curl), so the binary checks itself. It always
// targets loopback; only the port is taken from LISTEN_ADDR, and it must be
// a valid number.
func healthcheck() int {
	port := 8080
	if addr := os.Getenv("LISTEN_ADDR"); addr != "" {
		_, p, err := net.SplitHostPort(addr)
		if err != nil {
			return 1
		}
		n, err := strconv.Atoi(p)
		if err != nil || n < 1 || n > 65535 {
			return 1
		}
		port = n
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	url := "http://" + net.JoinHostPort("127.0.0.1", strconv.Itoa(port)) + "/healthz"
	// Not SSRF: the host is always loopback; only a validated port comes from config.
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil) //nolint:gosec // G704: fixed loopback target
	if err != nil {
		return 1
	}
	resp, err := http.DefaultClient.Do(req) //nolint:gosec // G704: fixed loopback target
	if err != nil {
		return 1
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
