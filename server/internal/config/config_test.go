package config

import (
	"testing"
	"time"
)

func TestLoadRequiresDatabaseURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	if _, err := Load(); err == nil {
		t.Fatal("expected error without DATABASE_URL")
	}
}

func TestLoadDefaultsAndOverrides(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("TOKEN_TTL", "5m")
	t.Setenv("RATE_LIMIT_PER_MINUTE", "")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.TokenTTL != 5*time.Minute || c.ChallengeTTL != time.Minute || c.RateLimitPerMinute != 30 || c.ListenAddr != ":8080" {
		t.Fatalf("unexpected config %+v", c)
	}
}

func TestLoadRejectsBadValues(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	for k, v := range map[string]string{"TOKEN_TTL": "-1s", "CHALLENGE_TTL": "soon", "RATE_LIMIT_PER_MINUTE": "0"} {
		t.Run(k, func(t *testing.T) {
			t.Setenv(k, v)
			if _, err := Load(); err == nil {
				t.Fatalf("%s=%s accepted", k, v)
			}
		})
	}
}
