package messaging_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"whispr/server/internal/auth"
	"whispr/server/internal/messaging"
	"whispr/server/internal/sigverify/sigverifytest"
)

// Security tests against the full router, real Postgres and real sockets:
// session lifetime, revocation, account deletion, quotas, hostile frames
// and cross-user access.

func (h *harness) do(method, path, token string, body any) int {
	h.t.Helper()
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req, _ := http.NewRequest(method, h.srv.URL+path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	_ = resp.Body.Close()
	return resp.StatusCode
}

// waitClosed waits for the server to close the client's socket.
func (c *client) waitClosed(what string) {
	c.t.Helper()
	select {
	case <-c.done:
	case <-time.After(5 * time.Second):
		c.t.Fatalf("socket survived %s", what)
	}
}

func TestSocketClosesWhenItsTokenExpires(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{tokenTTL: 2 * time.Second})
	alice := h.newUser("Alice")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	url := "ws" + strings.TrimPrefix(h.srv.URL, "http") + "/v1/ws"
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + alice.token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = conn.CloseNow() }()
	start := time.Now()
	for {
		if _, _, err = conn.Read(ctx); err != nil {
			break
		}
	}
	if code := websocket.CloseStatus(err); code != messaging.StatusTokenExpired {
		t.Fatalf("close status %d (%v), want %d", code, err, messaging.StatusTokenExpired)
	}
	if time.Since(start) > 5*time.Second {
		t.Fatalf("closed after %v, long after the token expired", time.Since(start))
	}
	if _, err := h.tryDial(alice.token); err == nil {
		t.Fatal("expired token opened a socket")
	}
}

func TestLogoutRevokesTokenAndClosesSocket(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice := h.newUser("Alice")
	a := h.dial(alice)
	if code := h.do(http.MethodPost, "/v1/auth/logout", alice.token, nil); code != http.StatusNoContent {
		t.Fatalf("logout status %d", code)
	}
	a.waitClosed("logout")
	if code := h.do(http.MethodGet, "/v1/me", alice.token, nil); code != http.StatusUnauthorized {
		t.Fatalf("revoked token: status %d", code)
	}
	if _, err := h.tryDial(alice.token); err == nil {
		t.Fatal("revoked token opened a socket")
	}
}

func TestAccountDeletionRemovesEverythingAndNeedsAFreshSignature(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	ctx := context.Background()

	// Queue mail both ways so the cascade has something to remove.
	a := h.dial(alice)
	a.send(uuid.New(), uuid.New(), bob, "to bob")
	a.next("accepted")
	b := h.dial(bob)
	b.send(uuid.New(), uuid.New(), alice, "to alice")
	b.next("accepted")
	if code := h.do(http.MethodPut, "/v1/push/token", alice.token, map[string]string{"provider": "fcm", "token": "t"}); code != http.StatusNoContent {
		t.Fatalf("push token: %d", code)
	}

	// The bearer token alone is not enough, nor a sign-in signature.
	if code := h.do(http.MethodDelete, "/v1/me", alice.token, map[string]any{}); code != http.StatusBadRequest {
		t.Fatalf("no challenge: status %d", code)
	}
	c, err := h.auth.Challenge(ctx, alice.id)
	if err != nil {
		t.Fatal(err)
	}
	sig := sigverifytest.Sign(alice.key, auth.AuthMessage(alice.id, c.Nonce))
	if code := h.do(http.MethodDelete, "/v1/me", alice.token, map[string]any{"challenge_id": c.ID, "signature": sig}); code != http.StatusUnauthorized {
		t.Fatalf("auth signature: status %d", code)
	}
	// Bob's token cannot delete Alice, even with her signed challenge.
	c, _ = h.auth.Challenge(ctx, alice.id)
	sig = sigverifytest.Sign(alice.key, auth.DeleteMessage(alice.id, c.Nonce))
	if code := h.do(http.MethodDelete, "/v1/me", bob.token, map[string]any{"challenge_id": c.ID, "signature": sig}); code != http.StatusUnauthorized {
		t.Fatalf("cross-user delete: status %d", code)
	}

	c, _ = h.auth.Challenge(ctx, alice.id)
	sig = sigverifytest.Sign(alice.key, auth.DeleteMessage(alice.id, c.Nonce))
	if code := h.do(http.MethodDelete, "/v1/me", alice.token, map[string]any{"challenge_id": c.ID, "signature": sig}); code != http.StatusNoContent {
		t.Fatalf("delete: status %d", code)
	}
	a.waitClosed("account deletion")
	for _, q := range []string{
		"SELECT count(*) FROM users WHERE id = $1",
		"SELECT count(*) FROM auth_tokens WHERE user_id = $1",
		"SELECT count(*) FROM auth_challenges WHERE user_id = $1",
		"SELECT count(*) FROM envelopes WHERE sender_id = $1 OR recipient_id = $1",
		"SELECT count(*) FROM accepted_messages WHERE sender_id = $1",
		"SELECT count(*) FROM push_tokens WHERE user_id = $1",
	} {
		var n int
		if err := h.pool.QueryRow(ctx, q, alice.id).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 0 {
			t.Errorf("%q: %d rows survive the deletion", q, n)
		}
	}
	if code := h.do(http.MethodGet, "/v1/users/"+alice.id.String(), bob.token, nil); code != http.StatusNotFound {
		t.Fatalf("lookup of deleted user: %d", code)
	}
	if code := h.do(http.MethodGet, "/v1/me", bob.token, nil); code != http.StatusOK {
		t.Fatalf("bob affected: %d", code)
	}
}

func TestUndeliveredQuotaPerSenderAndRecipient(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{quota: 3})
	alice, bob, carol := h.newUser("Alice"), h.newUser("Bob"), h.newUser("Carol")
	a := h.dial(alice)
	conv := uuid.New()
	var first uuid.UUID
	for i := range 3 {
		id := uuid.New()
		if i == 0 {
			first = id
		}
		a.send(id, conv, bob, "x")
		a.next("accepted")
	}
	over := uuid.New()
	a.send(over, conv, bob, "x")
	if r := a.next("rejected"); r["code"] != "recipient_full" || r["id"] != over.String() {
		t.Fatalf("over quota: %v", r)
	}
	// A retry of an accepted message is still acknowledged.
	a.send(first, conv, bob, "x")
	if acc := a.next("accepted"); acc["id"] != first.String() {
		t.Fatalf("retry: %v", acc)
	}
	// Other recipients and other senders are unaffected.
	a.send(uuid.New(), uuid.New(), carol, "x")
	a.next("accepted")
	c := h.dial(carol)
	c.send(uuid.New(), uuid.New(), bob, "x")
	c.next("accepted")
	// A fan-out that includes the full recipient is rejected as a whole.
	a.write(map[string]any{
		"type": "send_multi", "id": uuid.New(), "conversation_id": uuid.New(),
		"recipient_ids": []uuid.UUID{carol.id, bob.id}, "client_ts": time.Now().UTC(), "payload": []byte("g"),
	})
	if r := a.next("rejected"); r["code"] != "recipient_full" {
		t.Fatalf("fan-out over quota: %v", r)
	}
	// Delivery frees room.
	b := h.dial(bob)
	for range 4 { // Alice's three and Carol's one
		b.ack(b.next("envelope")["seq"].(float64))
	}
	eventually(t, "quota freed after delivery", func() bool {
		var n int
		_ = h.pool.QueryRow(context.Background(), "SELECT count(*) FROM envelopes WHERE recipient_id = $1 AND kind = 1", bob.id).Scan(&n)
		return n == 0
	})
	a.send(uuid.New(), conv, bob, "x")
	a.next("accepted")
}

func TestHostileFramesAreRejectedOrDropTheConnection(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")

	// Field-level problems get a rejection; the connection stays up.
	a := h.dial(alice)
	now := time.Now().UTC()
	many := make([]uuid.UUID, messaging.MaxRecipients+1)
	for i := range many {
		many[i] = uuid.New()
	}
	invalid := map[string]map[string]any{
		"zero id":              {"type": "send", "id": uuid.Nil, "conversation_id": uuid.New(), "recipient_id": bob.id, "client_ts": now},
		"zero conversation":    {"type": "send", "id": uuid.New(), "conversation_id": uuid.Nil, "recipient_id": bob.id, "client_ts": now},
		"no timestamp":         {"type": "send", "id": uuid.New(), "conversation_id": uuid.New(), "recipient_id": bob.id},
		"ancient timestamp":    {"type": "send", "id": uuid.New(), "conversation_id": uuid.New(), "recipient_id": bob.id, "client_ts": time.Date(1999, 1, 1, 0, 0, 0, 0, time.UTC)},
		"future timestamp":     {"type": "send", "id": uuid.New(), "conversation_id": uuid.New(), "recipient_id": bob.id, "client_ts": now.Add(48 * time.Hour)},
		"too many recipients":  {"type": "send_multi", "id": uuid.New(), "conversation_id": uuid.New(), "recipient_ids": many, "client_ts": now},
		"duplicate recipients": {"type": "send_multi", "id": uuid.New(), "conversation_id": uuid.New(), "recipient_ids": []uuid.UUID{bob.id, bob.id}, "client_ts": now},
		"empty fan-out":        {"type": "send_multi", "id": uuid.New(), "conversation_id": uuid.New(), "recipient_ids": []uuid.UUID{}, "client_ts": now},
	}
	for name, f := range invalid {
		a.write(f)
		if r := a.next("rejected"); r["code"] != "invalid" {
			t.Errorf("%s: %v", name, r)
		}
	}
	a.send(uuid.New(), uuid.New(), bob, "still connected")
	a.next("accepted")

	// Protocol violations drop the connection.
	violations := map[string]func(*websocket.Conn) error{
		"malformed json": func(c *websocket.Conn) error {
			return c.Write(context.Background(), websocket.MessageText, []byte("{not json"))
		},
		"binary frame": func(c *websocket.Conn) error {
			return c.Write(context.Background(), websocket.MessageBinary, []byte("{}"))
		},
		"unknown type": func(c *websocket.Conn) error {
			return c.Write(context.Background(), websocket.MessageText, []byte(`{"type":"admin"}`))
		},
		"oversized frame": func(c *websocket.Conn) error {
			return c.Write(context.Background(), websocket.MessageText, []byte(`{"type":"send","payload":"`+strings.Repeat("A", 200_000)+`"}`))
		},
	}
	for name, violate := range violations {
		c := h.dial(alice)
		if err := violate(c.conn); err != nil {
			t.Fatalf("%s: write: %v", name, err)
		}
		c.waitClosed(name)
	}
}

func TestCannotAckSomeoneElsesEnvelope(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob, mallory := h.newUser("Alice"), h.newUser("Bob"), h.newUser("Mallory")
	a := h.dial(alice)
	a.send(uuid.New(), uuid.New(), bob, "for bob")
	seq := a.next("accepted")["seq"].(float64)

	m := h.dial(mallory)
	m.ack(seq)
	m.ack(seq + 1)
	time.Sleep(200 * time.Millisecond)

	b := h.dial(bob)
	if env := b.next("envelope"); payloadText(env) != "for bob" {
		t.Fatalf("bob got %v", env)
	}
	a.expectNone("envelope", 300*time.Millisecond) // no forged "delivered" receipt
}

func TestExpiredAndDeletedUsersTokensAreRefused(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{tokenTTL: time.Second})
	alice := h.newUser("Alice")
	time.Sleep(1100 * time.Millisecond)
	for _, path := range []string{"/v1/me", "/v1/me/profile", "/v1/keys/count", "/v1/users/" + uuid.NewString()} {
		if code := h.do(http.MethodGet, path, alice.token, nil); code != http.StatusUnauthorized {
			t.Errorf("expired token on %s: %d", path, code)
		}
	}

	h2 := newServer(t, testPool(t), harnessOpts{})
	bob := h2.newUser("Bob")
	if _, err := h2.pool.Exec(context.Background(), "DELETE FROM users WHERE id = $1", bob.id); err != nil {
		t.Fatal(err)
	}
	if code := h2.do(http.MethodGet, "/v1/me", bob.token, nil); code != http.StatusUnauthorized {
		t.Errorf("deleted user's token: %d", code)
	}
	if _, err := h2.tryDial(bob.token); err == nil {
		t.Error("deleted user's token opened a socket")
	}
}
