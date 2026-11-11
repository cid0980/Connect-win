package main

import (
	"context"
	"net"
	"os"
	"strings"
	"time"
)

// Android has no /etc/resolv.conf, causing Go's pure-Go resolver to fall back
// to 127.0.0.1:53 (connection refused). Connect-win passes the active network's
// DNS servers + public fallbacks via CONNECTWIN_DNS, and exports AndroidCAStore
// root certificates to SSL_CERT_FILE so cloudflared works on Wi-Fi & 4G/5G.
func init() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	raw := os.Getenv("CONNECTWIN_DNS")
	if raw == "" {
		raw = "1.1.1.1:53,8.8.8.8:53,9.9.9.9:53"
	}
	var servers []string
	for _, s := range strings.Split(raw, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if !strings.Contains(s, ":") {
			s = s + ":53"
		}
		servers = append(servers, s)
	}
	if len(servers) == 0 {
		servers = []string{"1.1.1.1:53", "8.8.8.8:53"}
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 4 * time.Second}
			proto := "udp"
			if strings.HasPrefix(network, "tcp") {
				proto = "tcp"
			}
			var lastErr error
			for _, srv := range servers {
				conn, err := d.DialContext(ctx, proto, srv)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}
