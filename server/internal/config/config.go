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
}

// Load reads configuration from the environment. DATABASE_URL is required;
// everything else has a safe default.
func Load() (Config, error) {
	c := Config{
		ListenAddr:         getenv("LISTEN_ADDR", ":8080"),
		DatabaseURL:        os.Getenv("DATABASE_URL"),
		LogLevel:           getenv("LOG_LEVEL", "info"),
		TokenTTL:           15 * time.Minute,
		ChallengeTTL:       60 * time.Second,
		RateLimitPerMinute: 30,
	}
	if c.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	var err error
	if c.TokenTTL, err = duration("TOKEN_TTL", c.TokenTTL); err != nil {
		return Config{}, err
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
