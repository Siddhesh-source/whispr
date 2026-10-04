package logging

import (
	"bytes"
	"strings"
	"testing"
)

func TestRedactsSensitiveAttributes(t *testing.T) {
	var buf bytes.Buffer
	log := New(&buf, "debug")

	log.Info("event", "token", "tok-secret-123", "Signature", "sig-abc", "nonce", "n0nce", "route", "/v1/me")

	out := buf.String()
	for _, leaked := range []string{"tok-secret-123", "sig-abc", "n0nce"} {
		if strings.Contains(out, leaked) {
			t.Fatalf("log output leaked %q: %s", leaked, out)
		}
	}
	if !strings.Contains(out, "/v1/me") {
		t.Fatalf("non-sensitive attribute was dropped: %s", out)
	}
}

func TestInvalidLevelFallsBackToInfo(t *testing.T) {
	var buf bytes.Buffer
	log := New(&buf, "nonsense")
	log.Debug("hidden")
	log.Info("shown")
	if strings.Contains(buf.String(), "hidden") || !strings.Contains(buf.String(), "shown") {
		t.Fatalf("unexpected output: %s", buf.String())
	}
}
