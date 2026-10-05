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

func TestLoadMediaSettings(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("S3_ENDPOINT", "")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.S3Endpoint != "" || c.AttachmentRetention != 30*24*time.Hour || c.S3Bucket != "whispr-media" || !c.S3UseSSL {
		t.Fatalf("unexpected media defaults %+v", c)
	}

	t.Setenv("S3_ENDPOINT", "minio:9000")
	t.Setenv("S3_ACCESS_KEY", "")
	if _, err := Load(); err == nil {
		t.Fatal("S3_ENDPOINT without credentials accepted")
	}
	t.Setenv("S3_ACCESS_KEY", "a")
	t.Setenv("S3_SECRET_KEY", "b")
	t.Setenv("S3_USE_SSL", "false")
	t.Setenv("ATTACHMENT_RETENTION", "72h")
	c, err = Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.S3UseSSL || c.AttachmentRetention != 72*time.Hour {
		t.Fatalf("overrides ignored %+v", c)
	}
	t.Setenv("ATTACHMENT_RETENTION", "never")
	if _, err := Load(); err == nil {
		t.Fatal("bad retention accepted")
	}
}
