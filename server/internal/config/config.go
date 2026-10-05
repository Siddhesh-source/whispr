// Package config loads server configuration from environment variables.
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"time"
)

type Config struct {
	ListenAddr   string
	DatabaseURL  string
	TokenTTL     time.Duration
	ChallengeTTL time.Duration
	// RateLimitPerMinute applies per client IP to unauthenticated auth endpoints.
	RateLimitPerMinute int
	LogLevel           string
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
		ListenAddr:          getenv("LISTEN_ADDR", ":8080"),
		DatabaseURL:         os.Getenv("DATABASE_URL"),
		LogLevel:            getenv("LOG_LEVEL", "info"),
		FCMCredentialsFile:  os.Getenv("FCM_CREDENTIALS_FILE"),
		TokenTTL:            15 * time.Minute,
		ChallengeTTL:        60 * time.Second,
		RateLimitPerMinute:  30,
		S3Endpoint:          os.Getenv("S3_ENDPOINT"),
		S3AccessKey:         os.Getenv("S3_ACCESS_KEY"),
		S3SecretKey:         os.Getenv("S3_SECRET_KEY"),
		S3Bucket:            getenv("S3_BUCKET", "whispr-media"),
		S3UseSSL:            os.Getenv("S3_USE_SSL") != "false",
		AttachmentRetention: 30 * 24 * time.Hour,
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
	if v := os.Getenv("RATE_LIMIT_PER_MINUTE"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n <= 0 {
			return Config{}, fmt.Errorf("RATE_LIMIT_PER_MINUTE: invalid value %q", v)
		}
		c.RateLimitPerMinute = n
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
