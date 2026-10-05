package messaging_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"

	"whispr/server/internal/messaging"
)

// Group fan-out: one send_multi frame, one ciphertext, one envelope per
// recipient. The server stores no group state.

func (c *client) sendMulti(id, conv uuid.UUID, to []user, text string) {
	ids := make([]uuid.UUID, len(to))
	for i, u := range to {
		ids[i] = u.id
	}
	c.write(map[string]any{
		"type": "send_multi", "id": id, "conversation_id": conv, "recipient_ids": ids,
		"client_ts": time.Now().UTC(), "payload": []byte(text),
	})
}

func TestSendMultiFansOutToEveryRecipientWithReceipts(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob, carol := h.newUser("Alice"), h.newUser("Bob"), h.newUser("Carol")
	a, b, c := h.dial(alice), h.dial(bob), h.dial(carol)

	id, group := uuid.New(), uuid.New()
	a.sendMulti(id, group, []user{bob, carol}, "sealed")
	if acc := a.next("accepted"); acc["id"] != id.String() {
		t.Fatalf("accepted %v", acc)
	}
	for _, rc := range []*client{b, c} {
		env := rc.next("envelope")
		if env["id"] != id.String() || env["sender_id"] != alice.id.String() ||
			env["conversation_id"] != group.String() || payloadText(env) != "sealed" {
			t.Fatalf("envelope %v", env)
		}
		rc.ack(env["seq"].(float64))
	}
	// One delivery receipt per recipient, each naming who received it.
	from := map[any]bool{}
	for range 2 {
		r := a.next("envelope")
		if r["kind"] != "delivered" || r["ref_id"] != id.String() {
			t.Fatalf("receipt %v", r)
		}
		from[r["sender_id"]] = true
		a.ack(r["seq"].(float64))
	}
	if !from[bob.id.String()] || !from[carol.id.String()] {
		t.Fatalf("receipts from %v", from)
	}
}

func TestSendMultiRetryIsDeduplicated(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob, carol := h.newUser("Alice"), h.newUser("Bob"), h.newUser("Carol")
	a := h.dial(alice)
	id, group := uuid.New(), uuid.New()
	a.sendMulti(id, group, []user{bob, carol}, "once")
	first := a.next("accepted")
	a.sendMulti(id, group, []user{bob, carol}, "once")
	second := a.next("accepted")
	if first["seq"] != second["seq"] {
		t.Fatalf("retry got a new seq: %v vs %v", first, second)
	}
	var n int
	if err := h.pool.QueryRow(context.Background(), `SELECT count(*) FROM envelopes WHERE message_id = $1`, id).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Fatalf("stored %d copies, want 2", n)
	}
}

func TestSendMultiRejectsBadRecipientListsAtomically(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{sendRate: 1000, sendBurst: 1000})
	alice, bob := h.newUser("Alice"), h.newUser("Bob")
	a := h.dial(alice)
	ghost := user{id: uuid.New()}
	tooMany := make([]user, messaging.MaxRecipients+1)
	for i := range tooMany {
		tooMany[i] = user{id: uuid.New()}
	}
	cases := []struct {
		name string
		to   []user
		code string
	}{
		{"unknown recipient", []user{bob, ghost}, "unknown_recipient"},
		{"self", []user{bob, alice}, "self"},
		{"duplicate", []user{bob, bob}, "invalid"},
		{"empty", nil, "invalid"},
		{"too many", tooMany, "invalid"},
	}
	for _, tc := range cases {
		id := uuid.New()
		a.sendMulti(id, uuid.New(), tc.to, "x")
		rej := a.next("rejected")
		if rej["id"] != id.String() || rej["code"] != tc.code {
			t.Fatalf("%s: got %v, want %s", tc.name, rej, tc.code)
		}
	}
	// Nothing was stored for anyone, including the valid recipient.
	var n int
	if err := h.pool.QueryRow(context.Background(), `SELECT count(*) FROM envelopes`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("%d envelopes stored after rejected fan-outs", n)
	}
}

func TestSendMultiKeepsOrderWithOneToOneSends(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{sendRate: 1000, sendBurst: 1000})
	alice, bob, carol := h.newUser("Alice"), h.newUser("Bob"), h.newUser("Carol")
	a := h.dial(alice)
	// A key distribution message (1:1) must reach Bob before the group
	// message sent after it.
	a.send(uuid.New(), uuid.New(), bob, "skdm")
	a.next("accepted")
	a.sendMulti(uuid.New(), uuid.New(), []user{bob, carol}, "group")
	a.next("accepted")
	b := h.dial(bob)
	for _, want := range []string{"skdm", "group"} {
		env := b.next("envelope")
		if payloadText(env) != want {
			t.Fatalf("got %q, want %q", payloadText(env), want)
		}
		b.ack(env["seq"].(float64))
	}
}

func TestSendMultiWakesOfflineRecipients(t *testing.T) {
	h := newServer(t, testPool(t), harnessOpts{})
	alice, bob, carol := h.newUser("Alice"), h.newUser("Bob"), h.newUser("Carol")
	a := h.dial(alice)
	a.sendMulti(uuid.New(), uuid.New(), []user{bob, carol}, "wake")
	a.next("accepted")
	eventually(t, "both offline recipients woken", func() bool { return h.waker.n.Load() >= 2 })
}
