package httpx

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strings"
)

// ParseTrustedProxies parses a comma-separated list of CIDRs or single
// addresses ("10.0.0.0/8, 192.0.2.7"). An empty string trusts no proxy.
func ParseTrustedProxies(s string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if !strings.Contains(part, "/") {
			addr, err := netip.ParseAddr(part)
			if err != nil {
				return nil, fmt.Errorf("trusted proxy %q: %w", part, err)
			}
			out = append(out, netip.PrefixFrom(addr.Unmap(), addr.Unmap().BitLen()))
			continue
		}
		p, err := netip.ParsePrefix(part)
		if err != nil {
			return nil, fmt.Errorf("trusted proxy %q: %w", part, err)
		}
		out = append(out, p.Masked())
	}
	return out, nil
}

type clientIPKey struct{}

// ClientIP resolves the client's address once per request and stores it for
// the rate limiters. X-Forwarded-For is believed only when the TCP peer is a
// trusted proxy, and then only up to the first hop that is not itself a
// trusted proxy (walking from the right), so a client cannot choose its own
// address by sending the header. The address is never logged.
func ClientIP(trusted []netip.Prefix) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ip := resolveClientIP(r, trusted)
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), clientIPKey{}, ip)))
		})
	}
}

// ClientIPFrom returns the address set by ClientIP, or the TCP peer address
// when the middleware is not installed.
func ClientIPFrom(r *http.Request) string {
	if ip, ok := r.Context().Value(clientIPKey{}).(string); ok {
		return ip
	}
	return peerIP(r)
}

func resolveClientIP(r *http.Request, trusted []netip.Prefix) string {
	peer := peerIP(r)
	if len(trusted) == 0 || !isTrusted(peer, trusted) {
		return peer
	}
	var hops []string
	for _, h := range r.Header.Values("X-Forwarded-For") {
		for _, hop := range strings.Split(h, ",") {
			hops = append(hops, strings.TrimSpace(hop))
		}
	}
	for i := len(hops) - 1; i >= 0; i-- {
		addr, err := netip.ParseAddr(hops[i])
		if err != nil {
			// A malformed hop: stop trusting the chain here and use the
			// last address we could verify.
			return peer
		}
		if !isTrusted(addr.Unmap().String(), trusted) {
			return addr.Unmap().String()
		}
		peer = addr.Unmap().String()
	}
	return peer
}

func isTrusted(ip string, trusted []netip.Prefix) bool {
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return false
	}
	addr = addr.Unmap()
	for _, p := range trusted {
		if p.Contains(addr) {
			return true
		}
	}
	return false
}

func peerIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return addr.Unmap().String()
	}
	return host
}

// SecurityHeaders sets defensive headers on every response. The API serves
// JSON and binary blobs only, never HTML, so nothing may be framed, sniffed
// or loaded as a document. HSTS is the TLS terminator's job.
func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		next.ServeHTTP(w, r)
	})
}
