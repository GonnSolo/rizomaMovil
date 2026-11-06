package rizomacore

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/sha3"
	"crypto/sha512"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

// Rizoma Desktop publishes its onion service through bine, which talks to Tor's control port
// and runs:
//
//	ADD_ONION ED25519-V3:<base64 expanded key> Port=80,127.0.0.1:<local port>
//
// On Android, Tor runs inside tor-android's TorService, which exposes an unauthenticated control
// socket (--ControlSocket <getDir("TorService")>/ControlSocket --CookieAuthentication 0).
// torControlConn is a minimal control-protocol client (Tor control-spec) that performs exactly the
// same request, so a mobile host is reachable at the same .onion address Desktop would use for the
// same key (for example THE VOID's fixed address).

const torControlTimeout = 60 * time.Second

var onionIDEncoding = base32.StdEncoding.WithPadding(base32.NoPadding)

type torControlConn struct {
	conn   net.Conn
	reader *bufio.Reader
}

// dialTorControl connects to Tor's control interface. addr is "unix:/path/to/socket"
// (tor-android's ControlSocket), "tcp:host:port" or a bare "host:port".
func dialTorControl(ctx context.Context, addr string) (*torControlConn, error) {
	network, address := "tcp", strings.TrimPrefix(addr, "tcp:")
	if strings.HasPrefix(addr, "unix:") {
		network, address = "unix", strings.TrimPrefix(addr, "unix:")
	}
	if address == "" {
		return nil, fmt.Errorf("empty Tor control address")
	}
	var d net.Dialer
	conn, err := d.DialContext(ctx, network, address)
	if err != nil {
		return nil, err
	}
	return &torControlConn{conn: conn, reader: bufio.NewReader(conn)}, nil
}

func (c *torControlConn) Close() error { return c.conn.Close() }

// request sends one command and returns the text of every reply line (without the status code).
// Any final status other than 250 is returned as an error.
func (c *torControlConn) request(cmd string) ([]string, error) {
	if _, err := fmt.Fprintf(c.conn, "%s\r\n", cmd); err != nil {
		return nil, err
	}
	var lines []string
	for {
		line, err := c.reader.ReadString('\n')
		if err != nil {
			return nil, err
		}
		line = strings.TrimRight(line, "\r\n")
		if len(line) < 4 {
			return nil, fmt.Errorf("malformed Tor control reply: %q", line)
		}
		code, sep, text := line[:3], line[3], line[4:]
		if code[0] == '6' {
			continue // asynchronous event, not part of this reply
		}
		switch sep {
		case '-':
			lines = append(lines, text)
		case '+':
			// Data reply: lines until a single ".", with leading-dot unstuffing.
			var data []string
			for {
				dl, err := c.reader.ReadString('\n')
				if err != nil {
					return nil, err
				}
				dl = strings.TrimRight(dl, "\r\n")
				if dl == "." {
					break
				}
				data = append(data, strings.TrimPrefix(dl, "."))
			}
			lines = append(lines, text+strings.Join(data, "\n"))
		case ' ':
			lines = append(lines, text)
			if code != "250" {
				return nil, fmt.Errorf("Tor control error %s: %s", code, strings.Join(lines, "; "))
			}
			return lines, nil
		default:
			return nil, fmt.Errorf("malformed Tor control reply: %q", line)
		}
	}
}

// authenticate performs PROTOCOLINFO + AUTHENTICATE using NULL auth (tor-android) or the
// auth cookie file (a desktop Tor with CookieAuthentication 1).
func (c *torControlConn) authenticate() error {
	lines, err := c.request("PROTOCOLINFO 1")
	if err != nil {
		return fmt.Errorf("PROTOCOLINFO failed: %w", err)
	}
	var methods []string
	cookieFile := ""
	for _, l := range lines {
		rest, ok := strings.CutPrefix(l, "AUTH ")
		if !ok {
			continue
		}
		if i := strings.Index(rest, "METHODS="); i >= 0 {
			m := rest[i+len("METHODS="):]
			if j := strings.IndexByte(m, ' '); j >= 0 {
				m = m[:j]
			}
			methods = strings.Split(m, ",")
		}
		if i := strings.Index(rest, "COOKIEFILE="); i >= 0 {
			if v, ok := readControlQuoted(rest[i+len("COOKIEFILE="):]); ok {
				cookieFile = v
			}
		}
	}
	has := func(name string) bool {
		for _, m := range methods {
			if m == name {
				return true
			}
		}
		return false
	}

	switch {
	case has("NULL"):
		_, err = c.request("AUTHENTICATE")
	case has("COOKIE") && cookieFile != "":
		cookie, readErr := os.ReadFile(cookieFile)
		if readErr != nil {
			return fmt.Errorf("cannot read Tor auth cookie: %w", readErr)
		}
		_, err = c.request("AUTHENTICATE " + hex.EncodeToString(cookie))
	default:
		return fmt.Errorf("unsupported Tor control auth methods: %v", methods)
	}
	if err != nil {
		return fmt.Errorf("AUTHENTICATE failed: %w", err)
	}
	return nil
}

// readControlQuoted parses a control-spec QuotedString at the start of s.
func readControlQuoted(s string) (string, bool) {
	if !strings.HasPrefix(s, `"`) {
		return "", false
	}
	for i := 1; i < len(s); i++ {
		switch s[i] {
		case '\\':
			i++
		case '"':
			v, err := strconv.Unquote(s[:i+1])
			if err != nil {
				return "", false
			}
			return v, true
		}
	}
	return "", false
}

// onionKeyBlob encodes priv exactly like bine's control.ED25519Key, which Rizoma Desktop uses:
// base64 of the 64-byte expanded ed25519 secret, SHA-512(seed) with RFC 8032 clamping.
func onionKeyBlob(priv ed25519.PrivateKey) string {
	digest := sha512.Sum512(priv.Seed())
	digest[0] &= 248
	digest[31] &= 127
	digest[31] |= 64
	return base64.StdEncoding.EncodeToString(digest[:])
}

// onionServiceID returns the v3 onion service ID (without ".onion") for an ed25519 public key:
// base32(pubkey || checksum[:2] || 0x03), checksum = SHA3-256(".onion checksum" || pubkey || 0x03).
func onionServiceID(pub ed25519.PublicKey) string {
	checksum := sha3.Sum256(append(append([]byte(".onion checksum"), pub...), 0x03))
	buf := make([]byte, 0, 35)
	buf = append(buf, pub...)
	buf = append(buf, checksum[0], checksum[1], 0x03)
	return strings.ToLower(onionIDEncoding.EncodeToString(buf))
}

// publishOnionService authenticates to Tor and creates an onion service for priv that forwards
// virtual port virtPort to 127.0.0.1:localPort. The returned control connection owns the
// service: Tor removes the service when that connection is closed (no Detach flag, like bine).
func publishOnionService(ctx context.Context, controlAddr string, priv ed25519.PrivateKey, virtPort, localPort int) (string, *torControlConn, error) {
	if len(priv) != ed25519.PrivateKeySize {
		return "", nil, fmt.Errorf("invalid ed25519 private key size %d", len(priv))
	}
	if _, ok := ctx.Deadline(); !ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, torControlTimeout)
		defer cancel()
	}

	ctrl, err := dialTorControl(ctx, controlAddr)
	if err != nil {
		return "", nil, fmt.Errorf("cannot reach Tor control at %s: %w", controlAddr, err)
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = ctrl.conn.SetDeadline(deadline)
	}
	fail := func(e error) (string, *torControlConn, error) {
		ctrl.Close()
		return "", nil, e
	}

	if err := ctrl.authenticate(); err != nil {
		return fail(err)
	}

	lines, err := ctrl.request(fmt.Sprintf("ADD_ONION ED25519-V3:%s Port=%d,127.0.0.1:%d", onionKeyBlob(priv), virtPort, localPort))
	if err != nil {
		return fail(fmt.Errorf("ADD_ONION failed: %w", err))
	}
	serviceID := ""
	for _, l := range lines {
		if v, ok := strings.CutPrefix(l, "ServiceID="); ok {
			serviceID = v
		}
	}

	// Tor derives the address from the secret key, so it must match the key's own address.
	// A mismatch would publish the service somewhere peers (e.g. THE VOID's fixed address) never look.
	expected := onionServiceID(ed25519.NewKeyFromSeed(priv.Seed()).Public().(ed25519.PublicKey))
	if serviceID != expected {
		if serviceID != "" {
			_, _ = ctrl.request("DEL_ONION " + serviceID)
		}
		return fail(fmt.Errorf("Tor published %q but the key corresponds to %q", serviceID, expected))
	}

	_ = ctrl.conn.SetDeadline(time.Time{})
	return serviceID, ctrl, nil
}
