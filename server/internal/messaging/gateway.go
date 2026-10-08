package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
	"golang.org/x/time/rate"
)

// Gateway serves the authenticated WebSocket endpoint.
type Gateway struct {
	svc          *Service
	hub          *Hub
	log          *slog.Logger
	userID       func(context.Context) (uuid.UUID, bool)
	expiry       func(context.Context) (time.Time, bool)
	pingInterval time.Duration
	pingTimeout  time.Duration
	sendRate     rate.Limit
	sendBurst    int
}

type GatewayOptions struct {
	// UserID extracts the authenticated user set by the auth middleware.
	UserID func(context.Context) (uuid.UUID, bool)
	// TokenExpiry reports when the connection's bearer token expires; the
	// socket is closed then (status 4001) so the client re-authenticates.
	// Nil means connections never expire (tests only).
	TokenExpiry  func(context.Context) (time.Time, bool)
	PingInterval time.Duration
	PingTimeout  time.Duration
	// SendRate and SendBurst limit send/transient frames per connection.
	// Clients retry rate_limited sends with backoff.
	SendRate  rate.Limit
	SendBurst int
}

func NewGateway(svc *Service, hub *Hub, log *slog.Logger, opts GatewayOptions) *Gateway {
	if opts.PingInterval == 0 {
		opts.PingInterval = 25 * time.Second
	}
	if opts.PingTimeout == 0 {
		opts.PingTimeout = 10 * time.Second
	}
	if opts.SendRate == 0 {
		opts.SendRate, opts.SendBurst = 20, 40
	}
	return &Gateway{
		svc: svc, hub: hub, log: log, userID: opts.UserID, expiry: opts.TokenExpiry,
		pingInterval: opts.PingInterval, pingTimeout: opts.PingTimeout,
		sendRate: opts.SendRate, sendBurst: opts.SendBurst,
	}
}

const (
	pendingBatch = 100
	writeTimeout = 10 * time.Second
	// Base64 inflates payloads by 4/3; leave room for the JSON envelope.
	readLimit = MaxPayloadBytes*4/3 + 4096
	// StatusTokenExpired closes a socket whose bearer token expired. Clients
	// fetch a fresh token and reconnect at once.
	StatusTokenExpired websocket.StatusCode = 4001
)

var errTokenExpired = errors.New("messaging: token expired")

func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	user, ok := g.userID(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var expired <-chan time.Time
	if g.expiry != nil {
		exp, ok := g.expiry(r.Context())
		if !ok {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		t := time.NewTimer(time.Until(exp))
		defer t.Stop()
		expired = t.C
	}
	// The server's Read/WriteTimeout would otherwise stay attached to the
	// hijacked connection and kill every socket after a few seconds.
	rc := http.NewResponseController(w)
	_ = rc.SetReadDeadline(time.Time{})
	_ = rc.SetWriteDeadline(time.Time{})

	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return // Accept already wrote the HTTP error
	}
	conn.SetReadLimit(readLimit)

	ctx, cancel := context.WithCancel(context.WithoutCancel(r.Context()))
	defer cancel()
	sess := newSession(cancel)
	g.hub.register(user, sess)

	replies := make(chan any, 64)
	go func() {
		defer cancel()
		g.readLoop(ctx, conn, user, replies)
	}()
	err = g.writeLoop(ctx, conn, user, sess, replies, expired)

	// Mark the user offline before closing: a graceful close waits for the
	// peer's close frame, and a dead peer never sends one. Messages arriving
	// meanwhile must trigger a push, not wait on a ghost connection.
	g.hub.unregister(user, sess)
	cancel()
	if errors.Is(err, errTokenExpired) {
		_ = conn.Close(StatusTokenExpired, "token_expired")
		return
	}
	if err != nil && !errors.Is(err, context.Canceled) {
		_ = conn.CloseNow() // peer unresponsive or protocol error
		return
	}
	_ = conn.Close(websocket.StatusNormalClosure, "")
}

func (g *Gateway) readLoop(ctx context.Context, conn *websocket.Conn, user uuid.UUID, replies chan<- any) {
	limiter := rate.NewLimiter(g.sendRate, g.sendBurst)
	for {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		if typ != websocket.MessageText {
			return
		}
		var f clientFrame
		if err := json.Unmarshal(data, &f); err != nil {
			return // protocol violation: drop the connection
		}
		var reply any
		switch f.Type {
		case frameSend:
			if !limiter.Allow() {
				reply = rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeRateLimited}
				break
			}
			reply = g.handleSend(ctx, user, f)
		case frameSendMulti:
			switch {
			case len(f.RecipientIDs) > MaxRecipients:
				reply = rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeInvalid}
			// A fan-out costs more than one send, but much less than n.
			case !limiter.AllowN(time.Now(), 1+len(f.RecipientIDs)/10):
				reply = rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeRateLimited}
			default:
				reply = g.handleSendMulti(ctx, user, f)
			}
		case frameAck:
			if err := g.svc.Ack(ctx, user, f.Seq); err != nil {
				g.log.Error("ack failed", "err", err)
				return
			}
		case frameTransient:
			if limiter.Allow() && f.RecipientID != uuid.Nil && f.RecipientID != user && len(f.Payload) <= 1024 {
				frame, _ := json.Marshal(transientFrame{
					Type: frameTransient, SenderID: user, ConversationID: f.ConversationID, Payload: f.Payload,
				})
				g.hub.sendTransient(f.RecipientID, frame)
			}
		default:
			return
		}
		if reply != nil {
			select {
			case replies <- reply:
			case <-ctx.Done():
				return
			}
		}
	}
}

func (g *Gateway) handleSend(ctx context.Context, user uuid.UUID, f clientFrame) any {
	stored, err := g.svc.Accept(ctx, user, Envelope{
		MessageID: f.ID, ConversationID: f.ConversationID, RecipientID: f.RecipientID,
		ClientTS: f.ClientTS, Payload: f.Payload,
	})
	return g.sendReply(f, stored, err)
}

func (g *Gateway) handleSendMulti(ctx context.Context, user uuid.UUID, f clientFrame) any {
	stored, err := g.svc.AcceptMulti(ctx, user, Envelope{
		MessageID: f.ID, ConversationID: f.ConversationID, ClientTS: f.ClientTS, Payload: f.Payload,
	}, f.RecipientIDs)
	return g.sendReply(f, stored, err)
}

func (g *Gateway) sendReply(f clientFrame, stored Envelope, err error) any {
	switch {
	case err == nil:
		return acceptedFrame{Type: frameAccepted, ID: stored.MessageID, Seq: stored.Seq, ServerTS: stored.ServerTS.UTC()}
	case errors.Is(err, ErrInvalidEnvelope):
		return rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeInvalid}
	case errors.Is(err, ErrSelfRecipient):
		return rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeSelf}
	case errors.Is(err, ErrUnknownRecipient):
		return rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeUnknownRecipient}
	case errors.Is(err, ErrRecipientFull):
		return rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeRecipientFull}
	default:
		g.log.Error("accept failed", "err", err)
		return rejectedFrame{Type: frameRejected, ID: f.ID, Code: codeInternal}
	}
}

// writeLoop is the only goroutine that writes to conn. Envelopes are always
// read from the store in seq order starting after the last one sent on this
// connection, so backlog and live delivery share one ordered path.
func (g *Gateway) writeLoop(ctx context.Context, conn *websocket.Conn, user uuid.UUID, sess *session, replies <-chan any, expired <-chan time.Time) error {
	ping := time.NewTicker(g.pingInterval)
	defer ping.Stop()
	var cursor int64
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-expired:
			return errTokenExpired
		case <-sess.wake:
			for {
				batch, err := g.svc.Pending(ctx, user, cursor, pendingBatch)
				if err != nil {
					return err
				}
				for _, e := range batch {
					if err := writeJSON(ctx, conn, toEnvelopeFrame(e)); err != nil {
						return err
					}
					cursor = e.Seq
				}
				if len(batch) < pendingBatch {
					break
				}
			}
		case r := <-replies:
			if err := writeJSON(ctx, conn, r); err != nil {
				return err
			}
		case frame := <-sess.transient:
			wctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := conn.Write(wctx, websocket.MessageText, frame)
			cancel()
			if err != nil {
				return err
			}
		case <-ping.C:
			pctx, cancel := context.WithTimeout(ctx, g.pingTimeout)
			err := conn.Ping(pctx)
			cancel()
			if err != nil {
				return err // peer is gone; it will reconnect and drain
			}
		}
	}
}

func writeJSON(ctx context.Context, conn *websocket.Conn, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	return conn.Write(wctx, websocket.MessageText, data)
}
