package messaging_test

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/time/rate"

	"whispr/server/internal/auth"
	"whispr/server/internal/contacts"
	"whispr/server/internal/messaging"
	"whispr/server/internal/platform/dbtest"
	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/profile"
	"whispr/server/internal/push"
	"whispr/server/internal/server"
	"whispr/server/internal/sigverify/sigverifytest"
)

// Integration tests: real Postgres, real WebSockets, the full router with
// auth middleware. Each test gets its own database; run with
// WHISPR_TEST_DATABASE_URL set.

// testPool returns a fresh, migrated database for this test.
func testPool(t *testing.T) *pgxpool.Pool { return dbtest.New(t) }

type countingWaker struct{ n atomic.Int32 }

func (w *countingWaker) Wake(context.Context, uuid.UUID) error {
	w.n.Add(1)
	return nil
}

type harness struct {
	t     *testing.T
	pool  *pgxpool.Pool
	auth  *auth.Service
	srv   *httptest.Server
	waker *countingWaker
}

type harnessOpts struct {
	pingInterval, pingTimeout time.Duration
	serverTimeouts            time.Duration
	sendRate                  rate.Limit
	sendBurst                 int
}

// newServer starts a fresh server instance (new hub, new service) on pool,
// as a process restart would.
func newServer(t *testing.T, pool *pgxpool.Pool, o harnessOpts) *harness {
	t.Helper()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	authSvc := auth.NewService(auth.NewPGStore(pool), sigverifytest.Fake{}, auth.Options{TokenTTL: time.Hour, ChallengeTTL: time.Minute})
	hub := messaging.NewHub()
	waker := &countingWaker{}
	svc := messaging.NewService(messaging.NewPGStore(pool), hub, waker, log, messaging.Options{})
	gw := messaging.NewGateway(svc, hub, log, messaging.GatewayOptions{
		UserID: auth.UserIDFrom, PingInterval: o.pingInterval, PingTimeout: o.pingTimeout,
		SendRate: o.sendRate, SendBurst: o.sendBurst,
	})
	router := server.NewRouter(server.Deps{
		Log: log, DB: pool, Auth: authSvc, RateLimiter: httpx.NewRateLimiter(1000),
		Messaging: messaging.New(gw), Contacts: contacts.New(auth.NewPGStore(pool), log),
		Push:    push.NewModule(push.NewPGStore(pool), log, auth.UserIDFrom),
		Profile: profile.NewModule(profile.NewStore(pool), log, auth.UserIDFrom, httpx.NewRateLimiter(1000).Middleware),
	})
	srv := httptest.NewUnstartedServer(router)
	if o.serverTimeouts > 0 {
		srv.Config.ReadTimeout = o.serverTimeouts
		srv.Config.WriteTimeout = o.serverTimeouts
	}
	srv.Start()
	t.Cleanup(srv.Close)
	return &harness{t: t, pool: pool, auth: authSvc, srv: srv, waker: waker}
}

type user struct {
	id    uuid.UUID
	token string
}

func (h *harness) newUser(name string) user {
	h.t.Helper()
	ctx := context.Background()
	key := sigverifytest.NewPublicKey()
	u, _, err := h.auth.Register(ctx, key, name, sigverifytest.Sign(key, auth.RegisterMessage(key, name)))
	if err != nil {
		h.t.Fatal(err)
	}
	return user{id: u.ID, token: h.login(u.ID, key)}
}

func (h *harness) login(id uuid.UUID, key []byte) string {
	ctx := context.Background()
	c, err := h.auth.Challenge(ctx, id)
	if err != nil {
		h.t.Fatal(err)
	}
	tok, _, err := h.auth.Verify(ctx, c.ID, sigverifytest.Sign(key, auth.AuthMessage(id, c.Nonce)))
	if err != nil {
		h.t.Fatal(err)
	}
	return tok
}

// client is a minimal protocol client for tests.
type client struct {
	t      *testing.T
	conn   *websocket.Conn
	frames chan map[string]any
	done   chan struct{}
}

func (h *harness) dial(u user) *client {
	h.t.Helper()
	c, err := h.tryDial(u.token)
	if err != nil {
		h.t.Fatalf("dial: %v", err)
	}
	return c
}

func (h *harness) tryDial(token string) (*client, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	url := "ws" + strings.TrimPrefix(h.srv.URL, "http") + "/v1/ws"
	conn, resp, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + token}},
	})
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(1 << 20)
	c := &client{t: h.t, conn: conn, frames: make(chan map[string]any, 1024), done: make(chan struct{})}
	go func() {
		defer close(c.done)
		for {
			_, data, err := conn.Read(context.Background())
			if err != nil {
				return
			}
			var f map[string]any
			_ = json.Unmarshal(data, &f)
			c.frames <- f
		}
	}()
	h.t.Cleanup(func() { _ = conn.CloseNow() })
	return c, nil
}

func (c *client) write(v any) {
	c.t.Helper()
	data, _ := json.Marshal(v)
	if err := c.conn.Write(context.Background(), websocket.MessageText, data); err != nil {
		c.t.Fatalf("write: %v", err)
	}
}

func (c *client) send(id, conv uuid.UUID, to user, text string) {
	c.write(map[string]any{
		"type": "send", "id": id, "conversation_id": conv, "recipient_id": to.id,
		"client_ts": time.Now().UTC(), "payload": []byte(text),
	})
}

func (c *client) ack(seq float64) {
	c.write(map[string]any{"type": "ack", "seq": int64(seq)})
}

// next returns the next frame of the given type, failing after a timeout.
// Frames of other types are skipped.
func (c *client) next(typ string) map[string]any {
	c.t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case f := <-c.frames:
			if f["type"] == typ {
				return f
			}
		case <-deadline:
			c.t.Fatalf("timed out waiting for %q frame", typ)
		}
	}
}

// expectNone fails if a frame of the given type arrives within d.
func (c *client) expectNone(typ string, d time.Duration) {
	c.t.Helper()
	deadline := time.After(d)
	for {
		select {
		case f := <-c.frames:
			if f["type"] == typ {
				c.t.Fatalf("unexpected %q frame: %v", typ, f)
			}
		case <-deadline:
			return
		}
	}
}

func (c *client) close() {
	_ = c.conn.Close(websocket.StatusNormalClosure, "")
	<-c.done
}

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for: %s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func payloadText(f map[string]any) string {
	var b []byte
	raw, _ := json.Marshal(f["payload"])
	_ = json.Unmarshal(raw, &b)
	return string(b)
}

// ---- tests ----

func TestRealTimeDeliveryAndReceipts(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	a, b := h.dial(alice), h.dial(bob)

	id, conv := uuid.New(), uuid.New()
	a.send(id, conv, bob, "hello")
	acc := a.next("accepted")
	if acc["id"] != id.String() {
		t.Fatalf("accepted %v", acc)
	}

	env := b.next("envelope")
	if env["id"] != id.String() || env["sender_id"] != alice.id.String() || env["conversation_id"] != conv.String() ||
		env["kind"] != "envelope" || payloadText(env) != "hello" {
		t.Fatalf("envelope %v", env)
	}
	b.ack(env["seq"].(float64))

	receipt := a.next("envelope")
	if receipt["kind"] != "delivered" || receipt["ref_id"] != id.String() || receipt["sender_id"] != bob.id.String() {
		t.Fatalf("receipt %v", receipt)
	}
	a.ack(receipt["seq"].(float64))
}

func TestOfflineQueueDeliversExactlyOnceInOrderAfterReconnect(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{sendRate: 1000, sendBurst: 1000})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	a := h.dial(alice)
	conv := uuid.New()

	const n = 50
	for i := range n {
		a.send(uuid.New(), conv, bob, fmt.Sprintf("m%02d", i))
		a.next("accepted")
	}
	eventually(t, "offline recipient woken by push", func() bool { return h.waker.n.Load() > 0 })

	b := h.dial(bob)
	var lastSeq float64
	for i := range n {
		env := b.next("envelope")
		if got, want := payloadText(env), fmt.Sprintf("m%02d", i); got != want {
			t.Fatalf("message %d: got %q, want %q (out of order)", i, got, want)
		}
		if env["seq"].(float64) <= lastSeq {
			t.Fatalf("seq not increasing: %v after %v", env["seq"], lastSeq)
		}
		lastSeq = env["seq"].(float64)
		b.ack(lastSeq)
	}
	b.expectNone("envelope", 300*time.Millisecond)
	b.close()

	// Everything was acked: a new connection receives nothing again.
	b2 := h.dial(bob)
	b2.expectNone("envelope", 500*time.Millisecond)
}

func TestUnackedEnvelopesAreRedeliveredOnReconnect(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	a, b := h.dial(alice), h.dial(bob)
	id := uuid.New()
	a.send(id, uuid.New(), bob, "important")
	first := b.next("envelope")
	b.close() // connection lost before the client acked

	b2 := h.dial(bob)
	again := b2.next("envelope")
	if again["id"] != id.String() || again["seq"] != first["seq"] {
		t.Fatalf("redelivery mismatch: %v vs %v", again, first)
	}
	b2.ack(again["seq"].(float64))
}

func TestDuplicateSendsAreStoredAndDeliveredOnce(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	a, b := h.dial(alice), h.dial(bob)
	id, conv := uuid.New(), uuid.New()

	a.send(id, conv, bob, "once")
	first := a.next("accepted")
	a.send(id, conv, bob, "once") // client retry, e.g. ack lost on a flaky network
	second := a.next("accepted")
	if first["seq"] != second["seq"] {
		t.Fatalf("duplicate got a new seq: %v vs %v", first, second)
	}

	env := b.next("envelope")
	b.expectNone("envelope", 300*time.Millisecond)
	b.ack(env["seq"].(float64))

	// A retry arriving after delivery and ack must not be delivered again.
	a.send(id, conv, bob, "once")
	if third := a.next("accepted"); third["seq"] != first["seq"] {
		t.Fatalf("late duplicate got a new seq: %v", third)
	}
	b.expectNone("envelope", 300*time.Millisecond)
}

func TestServerRestartLosesNothing(t *testing.T) {
	pool := testPool(t)
	h1 := newServer(t, pool, harnessOpts{})
	alice, bob := h1.newUser("Alice"), h1.newUser("Bob")
	a := h1.dial(alice)
	conv := uuid.New()
	ids := []uuid.UUID{uuid.New(), uuid.New(), uuid.New()}
	for i, id := range ids {
		a.send(id, conv, bob, fmt.Sprintf("before-restart-%d", i))
		a.next("accepted") // "sent" means durably stored
	}

	// Kill the server abruptly: connections dropped, in-memory state gone.
	h1.srv.CloseClientConnections()
	h1.srv.Close()

	h2 := newServer(t, pool, harnessOpts{})
	b := h2.dial(bob) // tokens live in Postgres, so they survive too
	for i, id := range ids {
		env := b.next("envelope")
		if env["id"] != id.String() || payloadText(env) != fmt.Sprintf("before-restart-%d", i) {
			t.Fatalf("after restart, message %d: %v", i, env)
		}
		b.ack(env["seq"].(float64))
	}
	b.expectNone("envelope", 300*time.Millisecond)

	// Delivery receipts generated after the restart reach the sender.
	a2 := h2.dial(alice)
	for range ids {
		if r := a2.next("envelope"); r["kind"] != "delivered" {
			t.Fatalf("expected receipt, got %v", r)
		}
	}
}

func TestConcurrentSendersNeverSkipEnvelopes(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	bob := h.newUser("Bob")
	b := h.dial(bob)

	const senders, perSender = 8, 15
	var wg sync.WaitGroup
	for s := range senders {
		u := h.newUser(fmt.Sprintf("S%d", s))
		c := h.dial(u)
		wg.Go(func() {
			conv := uuid.New()
			for i := range perSender {
				c.send(uuid.New(), conv, bob, fmt.Sprintf("%d-%02d", s, i))
				c.next("accepted")
			}
		})
	}
	wg.Wait()

	seen := map[string]bool{}
	lastPerSender := map[string]int{}
	var lastSeq float64
	for range senders * perSender {
		env := b.next("envelope")
		if env["seq"].(float64) <= lastSeq {
			t.Fatalf("seq went backwards: %v after %v", env["seq"], lastSeq)
		}
		lastSeq = env["seq"].(float64)
		text := payloadText(env)
		if seen[text] {
			t.Fatalf("duplicate delivery of %s", text)
		}
		seen[text] = true
		var s, i int
		if _, err := fmt.Sscanf(text, "%d-%d", &s, &i); err != nil {
			t.Fatalf("unexpected payload %q", text)
		}
		key := env["sender_id"].(string)
		if prev, ok := lastPerSender[key]; ok && i != prev+1 {
			t.Fatalf("per-conversation order broken for sender %d: %d after %d", s, i, prev)
		}
		lastPerSender[key] = i
		b.ack(lastSeq)
	}
	b.expectNone("envelope", 300*time.Millisecond)
}

func TestTransientFramesAreNeverStored(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	a, b := h.dial(alice), h.dial(bob)
	conv := uuid.New()

	a.write(map[string]any{"type": "transient", "recipient_id": bob.id, "conversation_id": conv, "payload": []byte("typing")})
	tf := b.next("transient")
	if tf["sender_id"] != alice.id.String() || payloadText(tf) != "typing" {
		t.Fatalf("transient %v", tf)
	}

	b.close()
	a.write(map[string]any{"type": "transient", "recipient_id": bob.id, "conversation_id": conv, "payload": []byte("typing")})
	time.Sleep(100 * time.Millisecond)
	b2 := h.dial(bob)
	b2.expectNone("transient", 300*time.Millisecond)
	b2.expectNone("envelope", 100*time.Millisecond)
}

func TestRejections(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice := h.newUser("Alice")
	a := h.dial(alice)

	cases := map[string]map[string]any{
		"unknown_recipient": {"recipient_id": uuid.New(), "payload": []byte("x")},
		"self":              {"recipient_id": alice.id, "payload": []byte("x")},
		"invalid":           {"recipient_id": uuid.New(), "payload": make([]byte, messaging.MaxPayloadBytes+1)},
	}
	for code, fields := range cases {
		id := uuid.New()
		f := map[string]any{"type": "send", "id": id, "conversation_id": uuid.New(), "client_ts": time.Now().UTC()}
		for k, v := range fields {
			f[k] = v
		}
		a.write(f)
		r := a.next("rejected")
		if r["code"] != code || r["id"] != id.String() {
			t.Errorf("%s: got %v", code, r)
		}
	}

	if _, err := h.tryDial("not-a-token"); err == nil {
		t.Fatal("unauthenticated WebSocket connection was accepted")
	}
}

func TestNewConnectionReplacesOld(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	bob := h.newUser("Bob")
	first := h.dial(bob)
	h.dial(bob)
	select {
	case <-first.done:
	case <-time.After(3 * time.Second):
		t.Fatal("old connection was not closed")
	}
}

func TestHeartbeatDropsDeadPeers(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{pingInterval: 100 * time.Millisecond, pingTimeout: 100 * time.Millisecond})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")

	// A peer that never reads cannot answer pings, like a phone that lost
	// its network without closing the TCP connection.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	url := "ws" + strings.TrimPrefix(h.srv.URL, "http") + "/v1/ws"
	dead, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + bob.token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = dead.CloseNow() }()
	time.Sleep(500 * time.Millisecond)

	// Bob must now be treated as offline: a message triggers a push.
	a := h.dial(alice)
	a.send(uuid.New(), uuid.New(), bob, "are you there?")
	a.next("accepted")
	eventually(t, "dead peer treated as offline (push sent)", func() bool { return h.waker.n.Load() > 0 })
}

func TestConnectionSurvivesServerTimeouts(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{serverTimeouts: 500 * time.Millisecond})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	a, b := h.dial(alice), h.dial(bob)
	time.Sleep(1200 * time.Millisecond) // longer than Read/WriteTimeout
	a.send(uuid.New(), uuid.New(), bob, "still here")
	a.next("accepted")
	b.next("envelope")
}

func TestContactLookup(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	req, _ := http.NewRequest(http.MethodGet, h.srv.URL+"/v1/users/"+bob.id.String(), nil)
	req.Header.Set("Authorization", "Bearer "+alice.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	var body map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&body)
	if resp.StatusCode != 200 || body["display_name"] != "Bob" || body["identity_key"] == nil {
		t.Fatalf("lookup: %d %v", resp.StatusCode, body)
	}

	req, _ = http.NewRequest(http.MethodGet, h.srv.URL+"/v1/users/"+uuid.NewString(), nil)
	req.Header.Set("Authorization", "Bearer "+alice.token)
	resp2, _ := http.DefaultClient.Do(req)
	_ = resp2.Body.Close()
	if resp2.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown user: %d", resp2.StatusCode)
	}
}
