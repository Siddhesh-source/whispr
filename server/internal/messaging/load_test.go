package messaging_test

import (
	"context"
	"encoding/json"
	"math/rand/v2"
	"net/http"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
)

// TestGatewayLoad drives the real gateway, router and Postgres with many
// concurrent connections. It is opt-in (WHISPR_LOAD=1) because it takes a
// while and needs a database:
//
//	WHISPR_LOAD=1 WHISPR_LOAD_USERS=200 WHISPR_LOAD_MESSAGES=50 \
//	  WHISPR_TEST_DATABASE_URL=... go test -run TestGatewayLoad -v ./internal/messaging/
//
// Every user connects, sends WHISPR_LOAD_MESSAGES messages to random peers
// as fast as the per-connection rate limit allows, and acknowledges what it
// receives. It reports throughput and latency percentiles, and fails if any
// message is lost or delivered twice. It runs in process, so it measures the
// gateway and database, not TLS or a reverse proxy.
func TestGatewayLoad(t *testing.T) {
	if os.Getenv("WHISPR_LOAD") != "1" {
		t.Skip("set WHISPR_LOAD=1 to run the load test")
	}
	users := envInt("WHISPR_LOAD_USERS", 200)
	perUser := envInt("WHISPR_LOAD_MESSAGES", 50)
	// Generous limits: this measures the system, not the rate limiter.
	h := newServer(t, testPool(t), harnessOpts{sendRate: 10_000, sendBurst: 10_000})

	all := make([]user, users)
	for i := range all {
		all[i] = h.newUser("load" + strconv.Itoa(i))
	}

	type sent struct {
		at time.Time
		to int
	}
	var (
		sentMu     sync.Mutex
		sentAt     = map[uuid.UUID]sent{}
		received   sync.Map // uuid -> recipient index
		dups       atomic.Int64
		accLat     = newLatencies()
		e2eLat     = newLatencies()
		acceptedN  atomic.Int64
		receivedN  atomic.Int64
		rejectedN  atomic.Int64
		total      = int64(users * perUser)
		allArrived = make(chan struct{})
		closeOnce  sync.Once
		// Every send answered (accepted or rejected). Checked separately: a
		// recipient can receive a message before its sender reads "accepted".
		allAnswered = make(chan struct{})
		answerOnce  sync.Once
		answeredN   atomic.Int64
	)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	url := "ws" + strings.TrimPrefix(h.srv.URL, "http") + "/v1/ws"
	conns := make([]*websocket.Conn, users)
	var wmu = make([]sync.Mutex, users)
	for i, u := range all {
		c, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + u.token}}})
		if err != nil {
			t.Fatalf("dial %d: %v", i, err)
		}
		c.SetReadLimit(1 << 20)
		conns[i] = c
		defer func() { _ = c.CloseNow() }()
	}
	write := func(i int, v any) {
		data, _ := json.Marshal(v)
		wmu[i].Lock()
		defer wmu[i].Unlock()
		_ = conns[i].Write(ctx, websocket.MessageText, data)
	}

	var readers sync.WaitGroup
	for i := range users {
		readers.Add(1)
		go func() {
			defer readers.Done()
			for {
				_, data, err := conns[i].Read(ctx)
				if err != nil {
					return
				}
				var f struct {
					Type string    `json:"type"`
					ID   uuid.UUID `json:"id"`
					Seq  int64     `json:"seq"`
					Kind string    `json:"kind"`
				}
				if json.Unmarshal(data, &f) != nil {
					continue
				}
				switch f.Type {
				case "accepted":
					sentMu.Lock()
					s := sentAt[f.ID]
					sentMu.Unlock()
					accLat.add(time.Since(s.at))
					acceptedN.Add(1)
					if answeredN.Add(1) == total {
						answerOnce.Do(func() { close(allAnswered) })
					}
				case "rejected":
					rejectedN.Add(1)
					if answeredN.Add(1) == total {
						answerOnce.Do(func() { close(allAnswered) })
					}
				case "envelope":
					write(i, map[string]any{"type": "ack", "seq": f.Seq})
					if f.Kind != "envelope" {
						continue // a delivery receipt
					}
					if _, loaded := received.LoadOrStore(f.ID, i); loaded {
						dups.Add(1)
						continue
					}
					sentMu.Lock()
					s := sentAt[f.ID]
					sentMu.Unlock()
					if s.to != i {
						t.Errorf("message for %d delivered to %d", s.to, i)
					}
					e2eLat.add(time.Since(s.at))
					if receivedN.Add(1) == total {
						closeOnce.Do(func() { close(allArrived) })
					}
				}
			}
		}()
	}

	start := time.Now()
	var senders sync.WaitGroup
	for i := range users {
		senders.Add(1)
		go func() {
			defer senders.Done()
			for range perUser {
				to := rand.IntN(users - 1)
				if to >= i {
					to++
				}
				id := uuid.New()
				sentMu.Lock()
				sentAt[id] = sent{at: time.Now(), to: to}
				sentMu.Unlock()
				write(i, map[string]any{
					"type": "send", "id": id, "conversation_id": uuid.New(), "recipient_id": all[to].id,
					"client_ts": time.Now().UTC(), "payload": []byte("load test payload padded to look like a short message"),
				})
			}
		}()
	}
	senders.Wait()
	sendDone := time.Since(start)

	deadline := time.After(5 * time.Minute)
	select {
	case <-allArrived:
	case <-deadline:
		t.Errorf("only %d of %d messages arrived", receivedN.Load(), total)
	}
	select {
	case <-allAnswered:
	case <-deadline:
		t.Errorf("only %d of %d sends were answered", answeredN.Load(), total)
	}
	elapsed := time.Since(start)
	cancel()
	for _, c := range conns {
		_ = c.CloseNow()
	}
	readers.Wait()

	t.Logf("users=%d messages=%d sent_in=%v delivered_in=%v throughput=%.0f msg/s",
		users, total, sendDone.Round(time.Millisecond), elapsed.Round(time.Millisecond), float64(total)/elapsed.Seconds())
	t.Logf("accept latency:     %s", accLat.summary())
	t.Logf("end-to-end latency: %s", e2eLat.summary())
	if n := rejectedN.Load(); n != 0 {
		t.Errorf("%d sends rejected", n)
	}
	if n := acceptedN.Load(); n != total {
		t.Errorf("accepted %d of %d", n, total)
	}
	if n := dups.Load(); n != 0 {
		t.Errorf("%d duplicate deliveries", n)
	}
}

func envInt(key string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(key)); err == nil && v > 0 {
		return v
	}
	return def
}

type latencies struct {
	mu sync.Mutex
	d  []time.Duration
}

func newLatencies() *latencies { return &latencies{} }

func (l *latencies) add(d time.Duration) {
	l.mu.Lock()
	l.d = append(l.d, d)
	l.mu.Unlock()
}

func (l *latencies) summary() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.d) == 0 {
		return "no samples"
	}
	s := slices.Clone(l.d)
	slices.Sort(s)
	p := func(q float64) time.Duration { return s[int(q*float64(len(s)-1))].Round(100 * time.Microsecond) }
	return "p50=" + p(0.50).String() + " p95=" + p(0.95).String() + " p99=" + p(0.99).String() + " max=" + s[len(s)-1].Round(100*time.Microsecond).String()
}
