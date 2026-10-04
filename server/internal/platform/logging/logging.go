// Package logging configures structured logging with a deny-list redactor.
//
// Policy: handlers must never log request bodies, tokens, nonces, signatures,
// or key material. The redactor is a safety net for mistakes, not a licence
// to log sensitive values.
package logging

import (
	"io"
	"log/slog"
	"strings"
)

const redacted = "[REDACTED]"

// sensitiveKeys are attribute names whose values are always replaced.
var sensitiveKeys = map[string]struct{}{
	"authorization": {},
	"token":         {},
	"nonce":         {},
	"signature":     {},
	"identity_key":  {},
	"key":           {},
	"body":          {},
	"password":      {},
	"secret":        {},
	"ciphertext":    {},
	"display_name":  {},
}

// New returns a JSON logger writing to w at the given level name.
func New(w io.Writer, level string) *slog.Logger {
	var lvl slog.Level
	if err := lvl.UnmarshalText([]byte(level)); err != nil {
		lvl = slog.LevelInfo
	}
	return slog.New(slog.NewJSONHandler(w, &slog.HandlerOptions{
		Level:       lvl,
		ReplaceAttr: Redact,
	}))
}

// Redact replaces values of sensitive attributes. It is exported for tests
// and for use with other handlers.
func Redact(_ []string, a slog.Attr) slog.Attr {
	if _, ok := sensitiveKeys[strings.ToLower(a.Key)]; ok {
		return slog.String(a.Key, redacted)
	}
	return a
}
