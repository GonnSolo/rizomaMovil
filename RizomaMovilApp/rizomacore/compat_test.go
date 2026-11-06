package rizomacore

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// fakeTorControl is a scripted Tor control port: it records every command and answers with
// reply(cmd), which returns the raw reply lines (e.g. "250 OK").
type fakeTorControl struct {
	addr   string
	cmds   chan string
	closed chan struct{}
}

func startFakeTorControl(t *testing.T, reply func(cmd string) []string) *fakeTorControl {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	f := &fakeTorControl{addr: ln.Addr().String(), cmds: make(chan string, 32), closed: make(chan struct{})}
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer close(f.closed)
		defer conn.Close()
		r := bufio.NewReader(conn)
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				return
			}
			cmd := strings.TrimRight(line, "\r\n")
			f.cmds <- cmd
			for _, l := range reply(cmd) {
				fmt.Fprintf(conn, "%s\r\n", l)
			}
		}
	}()
	return f
}

func (f *fakeTorControl) next(t *testing.T) string {
	t.Helper()
	select {
	case c := <-f.cmds:
		return c
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for Tor control command")
		return ""
	}
}

func voidKey(t *testing.T) ed25519.PrivateKey {
	t.Helper()
	key, err := loadOrGenerateKey("VOID", nil)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

// standardReply answers like Tor with NULL auth and publishes serviceID for ADD_ONION.
func standardReply(serviceID string) func(string) []string {
	return func(cmd string) []string {
		switch {
		case cmd == "PROTOCOLINFO 1":
			return []string{"250-PROTOCOLINFO 1", "250-AUTH METHODS=NULL", `250-VERSION Tor="0.4.8.16"`, "250 OK"}
		case cmd == "AUTHENTICATE":
			return []string{"250 OK"}
		case strings.HasPrefix(cmd, "ADD_ONION "):
			return []string{"250-ServiceID=" + serviceID, "250 OK"}
		case strings.HasPrefix(cmd, "DEL_ONION "):
			return []string{"250 OK"}
		}
		return []string{"510 Unrecognized command"}
	}
}

// The mobile key encoding must yield THE VOID's address hard-coded in both Desktop and Mobile.
func TestVoidKeyMatchesDesktopVoidAddress(t *testing.T) {
	key := voidKey(t)
	id := onionServiceID(ed25519.NewKeyFromSeed(key.Seed()).Public().(ed25519.PublicKey))
	if id+".onion" != VoidAddress {
		t.Fatalf("void key derives %s.onion, Desktop uses %s", id, VoidAddress)
	}
}

func TestOnionKeyBlobIsClampedExpandedKey(t *testing.T) {
	raw, err := base64.StdEncoding.DecodeString(onionKeyBlob(voidKey(t)))
	if err != nil || len(raw) != 64 {
		t.Fatalf("blob must be base64 of 64 bytes (len=%d err=%v)", len(raw), err)
	}
	if raw[0]&7 != 0 || raw[31]&128 != 0 || raw[31]&64 == 0 {
		t.Fatalf("scalar is not clamped: %x … %x", raw[0], raw[31])
	}
}

func TestPublishOnionServiceSendsDesktopCommand(t *testing.T) {
	key := voidKey(t)
	want := strings.TrimSuffix(VoidAddress, ".onion")
	ctrl := startFakeTorControl(t, standardReply(want))

	id, conn, err := publishOnionService(context.Background(), "tcp:"+ctrl.addr, key, 80, 4321)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if id != want {
		t.Fatalf("service id %q, want %q", id, want)
	}
	if c := ctrl.next(t); c != "PROTOCOLINFO 1" {
		t.Fatalf("first command %q", c)
	}
	if c := ctrl.next(t); c != "AUTHENTICATE" {
		t.Fatalf("auth command %q", c)
	}
	wantCmd := "ADD_ONION ED25519-V3:" + onionKeyBlob(key) + " Port=80,127.0.0.1:4321"
	if c := ctrl.next(t); c != wantCmd {
		t.Fatalf("ADD_ONION command\n got: %s\nwant: %s", c, wantCmd)
	}
}

func TestPublishOnionServiceCookieAuth(t *testing.T) {
	cookie := []byte("0123456789abcdef0123456789abcdef")
	cookiePath := filepath.Join(t.TempDir(), "control_auth_cookie")
	if err := os.WriteFile(cookiePath, cookie, 0600); err != nil {
		t.Fatal(err)
	}
	want := strings.TrimSuffix(VoidAddress, ".onion")
	base := standardReply(want)
	ctrl := startFakeTorControl(t, func(cmd string) []string {
		switch {
		case cmd == "PROTOCOLINFO 1":
			return []string{"250-PROTOCOLINFO 1", "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=" + strconv.Quote(cookiePath), "250 OK"}
		case strings.HasPrefix(cmd, "AUTHENTICATE"):
			if cmd != "AUTHENTICATE "+hex.EncodeToString(cookie) {
				return []string{"515 Authentication failed"}
			}
			return []string{"250 OK"}
		}
		return base(cmd)
	})
	_, conn, err := publishOnionService(context.Background(), ctrl.addr, voidKey(t), 80, 1)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
}

func TestPublishRejectsUnexpectedServiceID(t *testing.T) {
	ctrl := startFakeTorControl(t, standardReply("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
	_, _, err := publishOnionService(context.Background(), "tcp:"+ctrl.addr, voidKey(t), 80, 1)
	if err == nil {
		t.Fatal("expected error when Tor publishes a different service id")
	}
	ctrl.next(t) // PROTOCOLINFO
	ctrl.next(t) // AUTHENTICATE
	ctrl.next(t) // ADD_ONION
	if c := ctrl.next(t); !strings.HasPrefix(c, "DEL_ONION aaaa") {
		t.Fatalf("expected DEL_ONION of the unexpected service, got %q", c)
	}
}

func TestPublishReportsTorErrors(t *testing.T) {
	ctrl := startFakeTorControl(t, func(cmd string) []string {
		if strings.HasPrefix(cmd, "ADD_ONION") {
			return []string{"550 Onion address collision"}
		}
		return standardReply("x")(cmd)
	})
	_, _, err := publishOnionService(context.Background(), "tcp:"+ctrl.addr, voidKey(t), 80, 1)
	if err == nil || !strings.Contains(err.Error(), "collision") {
		t.Fatalf("expected Tor's error to be reported, got %v", err)
	}
}

func TestHostWithoutTorControlFails(t *testing.T) {
	nm := NewNetworkManager()
	if err := nm.Host(context.Background(), "VOID", VoidSecret, nil, nil); err == nil {
		t.Fatal("Host must fail when the onion service cannot be published")
	}
	if nm.OnionAddr != "" || len(nm.hostListeners) != 0 {
		t.Fatal("failed Host must not leave a listener or address behind")
	}
}

func TestHostPublishesOnionAndCloseRemovesIt(t *testing.T) {
	want := strings.TrimSuffix(VoidAddress, ".onion")
	ctrl := startFakeTorControl(t, standardReply(want))
	nm := NewNetworkManager()
	nm.InitTorControl("tcp:" + ctrl.addr)
	if err := nm.Host(context.Background(), "VOID", VoidSecret, nil, nil); err != nil {
		t.Fatal(err)
	}
	if nm.OnionAddr != want || nm.MyPeerID != "host" {
		t.Fatalf("OnionAddr=%q MyPeerID=%q", nm.OnionAddr, nm.MyPeerID)
	}

	// The published port must reach the hosting handshake (secret required for THE VOID).
	conn, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", nm.LocalPort))
	if err != nil {
		t.Fatal(err)
	}
	greeting, _ := bufio.NewReader(conn).ReadString('\n')
	conn.Close()
	if strings.TrimSpace(greeting) != "AUTH_REQUIRED" {
		t.Fatalf("greeting %q", greeting)
	}

	nm.Close()
	select {
	case <-ctrl.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("Close must close the control connection so Tor removes the service")
	}
	if c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", nm.LocalPort), time.Second); err == nil {
		c.Close()
		t.Fatal("Close must stop the hosted listener")
	}
}

// fakeSOCKS records the CONNECT target of one SOCKS5 request and refuses it.
func fakeSOCKS(t *testing.T) (string, chan string) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	targets := make(chan string, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		hdr := make([]byte, 2)
		io.ReadFull(conn, hdr)
		io.ReadFull(conn, make([]byte, hdr[1]))
		conn.Write([]byte{5, 0})
		req := make([]byte, 4)
		io.ReadFull(conn, req)
		if req[3] != 3 { // x/net/proxy sends hostnames as ATYP=DOMAIN
			targets <- fmt.Sprintf("unexpected ATYP %d", req[3])
			return
		}
		l := make([]byte, 1)
		io.ReadFull(conn, l)
		host := make([]byte, l[0])
		io.ReadFull(conn, host)
		port := make([]byte, 2)
		io.ReadFull(conn, port)
		targets <- fmt.Sprintf("%s:%d", host, int(port[0])<<8|int(port[1]))
		conn.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0}) // host unreachable
	}()
	return ln.Addr().String(), targets
}

func TestConnectAddsOnionSuffixLikeDesktop(t *testing.T) {
	id := strings.TrimSuffix(VoidAddress, ".onion")
	for _, input := range []string{id, VoidAddress, "http://" + id + "/", " " + id + ":80 "} {
		socks, targets := fakeSOCKS(t)
		nm := NewNetworkManager()
		nm.InitProxy(socks)
		_ = nm.ConnectWithSecretAndroid(input, "")
		if got := <-targets; got != VoidAddress+":80" {
			t.Errorf("ConnectWithSecretAndroid(%q) dialed %q, want %q", input, got, VoidAddress+":80")
		}
	}
}

// pipePeer registers one end of a net.Pipe as a connection and returns every line the manager
// writes to it. net.Pipe is synchronous, so the other end is drained continuously.
func pipePeer(nm *NetworkManager, id string, key []byte) chan string {
	local, remote := net.Pipe()
	nm.mu.Lock()
	nm.Connections[id] = local
	nm.SessionKeys[id] = key
	nm.mu.Unlock()
	lines := make(chan string, 16)
	go func() {
		r := bufio.NewReader(remote)
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				return
			}
			lines <- strings.TrimRight(line, "\n")
		}
	}()
	return lines
}

func readLineWithin(lines chan string, d time.Duration) (string, bool) {
	select {
	case l := <-lines:
		return l, true
	case <-time.After(d):
		return "", false
	}
}

func TestHostRelaysLikeDesktop(t *testing.T) {
	key := DeriveSessionKey("rizoma_public_session")
	nm := NewNetworkManager()
	b := pipePeer(nm, "peer_b", key)
	host := pipePeer(nm, "host", key) // an outbound session: must never receive the hosted room
	a := pipePeer(nm, "peer_a", key)

	got := make(chan string, 4)
	nm.StartEventLoop(recordingCallback{peer: got})

	whisper, _ := json.Marshal(ChatPayload{Content: "solo para ti", Name: "Ana", Color: "205", Whisper: true})
	public, _ := json.Marshal(ChatPayload{Content: "hola a todos", Name: "Ana", Color: "205"})
	nm.Incoming <- "PEER_MSG:peer_a:Ana:205:" + string(whisper)
	nm.Incoming <- "PEER_MSG:peer_a:Ana:205:" + string(public)

	if m := <-got; m != "Ana|[whisper] solo para ti" {
		t.Fatalf("whisper delivered as %q", m)
	}
	if m := <-got; m != "Ana|hola a todos" {
		t.Fatalf("public message delivered as %q", m)
	}

	line, ok := readLineWithin(b, 2*time.Second)
	if !ok {
		t.Fatal("public message was not relayed to the other inbound peer")
	}
	plain, err := DecryptMessage(line, key)
	if err != nil || plain != string(public) {
		t.Fatalf("relayed %q (err %v), want the public message", plain, err)
	}
	if extra, ok := readLineWithin(b, 300*time.Millisecond); ok {
		t.Fatalf("whisper must not be relayed, peer_b also got %q", extra)
	}
	if extra, ok := readLineWithin(host, 300*time.Millisecond); ok {
		t.Fatalf("outbound session must not receive relayed traffic, got %q", extra)
	}
	if extra, ok := readLineWithin(a, 300*time.Millisecond); ok {
		t.Fatalf("sender must not get its own message back, got %q", extra)
	}
}

type recordingCallback struct{ peer chan string }

func (r recordingCallback) OnSystemMessage(string)                {}
func (r recordingCallback) OnFileOffer(string, int64, string)     {}
func (r recordingCallback) OnFileProgress(string, int64, int64)   {}
func (r recordingCallback) OnFileReceived(string, string, string) {}
func (r recordingCallback) OnPeerMessage(name, _ string, msg string) {
	r.peer <- name + "|" + msg
}

// Desktop sends empty files as file_size 0, total_chunks 0, no chunks, then file_end.
func TestEmptyFileFromDesktopIsSaved(t *testing.T) {
	dir := useTempDownloadDir(t)
	sum := sha256.Sum256(nil)
	checksum := hex.EncodeToString(sum[:])
	transfers := map[string]*FileTransferState{}
	if _, err := receiveFileChunkFrom("host", FileMessage{Type: "file_offer", Filename: "vacio.txt", FileSize: 0, TotalChunks: 0, Checksum: checksum}, transfers); err != nil {
		t.Fatalf("offer rejected: %v", err)
	}
	st, err := receiveFileChunkFrom("host", FileMessage{Type: "file_end", Filename: "vacio.txt", Checksum: checksum}, transfers)
	if err != nil || st == nil || !st.Completed {
		t.Fatalf("empty file not completed: %+v %v", st, err)
	}
	info, err := os.Stat(filepath.Join(dir, "vacio.txt"))
	if err != nil || info.Size() != 0 {
		t.Fatalf("empty file not saved: %v", err)
	}
}
