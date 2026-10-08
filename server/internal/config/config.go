// Package config loads server configuration from environment variables.
package config

import (
	"errors"
	"fmt"
	"net/netip"
	"os"
	"strconv"
	"time"

	"whispr/server/internal/platform/httpx"
)

type Config struct {
	ListenAddr   string
	DatabaseURL  string
	TokenTTL     time.Duration
	ChallengeTTL time.Duration
	// RateLimitPerMinute applies per client IP to unauthenticated auth endpoints.
	RateLimitPerMinute int
	// RegistrationsPerHour is a stricter per-IP limit on account creation.
	RegistrationsPerHour int
	// UserRequestsPerMinute bounds each user's authenticated REST calls.
	UserRequestsPerMinute int
	// TrustedProxies (TRUSTED_PROXIES, comma-separated CIDRs or addresses)
	// may set X-Forwarded-For. Set it to the TLS terminator's address, or
	// every client shares the proxy's rate limits.
	TrustedProxies []netip.Prefix
	LogLevel       string
	// FCMCredentialsFile is a Firebase service-account JSON key. Empty disables push.
	FCMCredentialsFile string
	// S3 (S3-compatible, MinIO in development) holds encrypted attachments.
	// An empty S3Endpoint disables media.
	S3Endpoint, S3AccessKey, S3SecretKey, S3Bucket string
	S3UseSSL                                       bool
	// AttachmentRetention is how long encrypted blobs are kept (default 30 days).
	AttachmentRetention time.Duration
}

// Load reads configuration from the environment. DATABASE_URL is required;
// everything else has a safe default.
func Load() (Config, error) {
	c := Config{
		ListenAddr:            getenv("LISTEN_ADDR", ":8080"),
		DatabaseURL:           os.Getenv("DATABASE_URL"),
		LogLevel:              getenv("LOG_LEVEL", "info"),
		FCMCredentialsFile:    os.Getenv("FCM_CREDENTIALS_FILE"),
		TokenTTL:              15 * time.Minute,
		ChallengeTTL:          60 * time.Second,
		RateLimitPerMinute:    30,
		RegistrationsPerHour:  10,
		UserRequestsPerMinute: 120,
		S3Endpoint:            os.Getenv("S3_ENDPOINT"),
		S3AccessKey:           os.Getenv("S3_ACCESS_KEY"),
		S3SecretKey:           os.Getenv("S3_SECRET_KEY"),
		S3Bucket:              getenv("S3_BUCKET", "whispr-media"),
		S3UseSSL:              os.Getenv("S3_USE_SSL") != "false",
		AttachmentRetention:   30 * 24 * time.Hour,
	}
	if c.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	var err error
	if c.TokenTTL, err = duration("TOKEN_TTL", c.TokenTTL); err != nil {
		return Config{}, err
	}
	if c.AttachmentRetention, err = duration("ATTACHMENT_RETENTION", c.AttachmentRetention); err != nil {
		return Config{}, err
	}
	if c.S3Endpoint != "" && (c.S3AccessKey == "" || c.S3SecretKey == "") {
		return Config{}, errors.New("S3_ACCESS_KEY and S3_SECRET_KEY are required with S3_ENDPOINT")
	}
	if c.ChallengeTTL, err = duration("CHALLENGE_TTL", c.ChallengeTTL); err != nil {
		return Config{}, err
	}
	for key, dst := range map[string]*int{
		"RATE_LIMIT_PER_MINUTE":    &c.RateLimitPerMinute,
		"REGISTRATIONS_PER_HOUR":   &c.RegistrationsPerHour,
		"USER_REQUESTS_PER_MINUTE": &c.UserRequestsPerMinute,
	} {
		if v := os.Getenv(key); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil || n <= 0 {
				return Config{}, fmt.Errorf("%s: invalid value %q", key, v)
			}
			*dst = n
		}
	}
	if c.TrustedProxies, err = httpx.ParseTrustedProxies(os.Getenv("TRUSTED_PROXIES")); err != nil {
		return Config{}, fmt.Errorf("TRUSTED_PROXIES: %w", err)
	}
	return c, nil
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func duration(key string, def time.Duration) (time.Duration, error) {
	v := os.Getenv(key)
	if v == "" {
		return def, nil
	}
	d, err := time.ParseDuration(v)
	if err != nil || d <= 0 {
		return 0, fmt.Errorf("%s: invalid duration %q", key, v)
	}
	return d, nil
}
