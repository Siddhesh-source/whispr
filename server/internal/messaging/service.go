package messaging

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
)

// MaxPayloadBytes bounds one envelope's opaque payload. Media goes to object
// storage separately (Phase 3+); envelopes carry text and references.
const MaxPayloadBytes = 64 << 10

// MaxRecipients bounds one fan-out (the largest group, minus the sender).
const MaxRecipients = 100

var (
	ErrInvalidEnvelope = errors.New("messaging: invalid envelope")
	ErrSelfRecipient   = errors.New("messaging: cannot send to self")
)

// Waker sends a content-free push to wake an offline device.
type Waker interface {
	Wake(ctx context.Context, user uuid.UUID) error
}

type NoopWaker struct{}

func (NoopWaker) Wake(context.Context, uuid.UUID) error { return nil }

type Service struct {
	store     Store
	hub       *Hub
	waker     Waker
	log       *slog.Logger
	now       func() time.Time
	retention time.Duration

	wakeMu   sync.Mutex
	lastWake map[uuid.UUID]time.Time
}

type Options struct {
	// Retention is how long undelivered envelopes and dedup tombstones live.
	Retention time.Duration
	Now       func() time.Time
}

func NewService(store Store, hub *Hub, waker Waker, log *slog.Logger, opts Options) *Service {
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.Retention == 0 {
		opts.Retention = 30 * 24 * time.Hour
	}
	if waker == nil {
		waker = NoopWaker{}
	}
	return &Service{
		store: store, hub: hub, waker: waker, log: log, now: opts.Now, retention: opts.Retention,
		lastWake: map[uuid.UUID]time.Time{},
	}
}

// Accept validates and persists an envelope from sender, then wakes the
// recipient. sender always comes from the authenticated connection, never
// from client input.
func (s *Service) Accept(ctx context.Context, sender uuid.UUID, e Envelope) (Envelope, error) {
	if e.MessageID == uuid.Nil || e.ConversationID == uuid.Nil || e.RecipientID == uuid.Nil ||
		e.ClientTS.IsZero() || len(e.Payload) > MaxPayloadBytes {
		return Envelope{}, ErrInvalidEnvelope
	}
	if e.RecipientID == sender {
		return Envelope{}, ErrSelfRecipient
	}
	e.SenderID = sender
	e.Kind = KindEnvelope
	e.RefMessageID = uuid.NullUUID{}
	e.ServerTS = s.now().UTC()
	if e.Payload == nil {
		e.Payload = []byte{}
	}
	stored, dup, err := s.store.Accept(ctx, e)
	if err != nil {
		return Envelope{}, err
	}
	if !dup {
		s.deliver(ctx, stored.RecipientID)
	}
	return stored, nil
}

// AcceptMulti validates and persists one envelope fanned out to several
// recipients (a group message encrypted once with a sender key). The server
// learns the recipient set, which it needs for delivery, and nothing about
// the group itself.
func (s *Service) AcceptMulti(ctx context.Context, sender uuid.UUID, e Envelope, recipients []uuid.UUID) (Envelope, error) {
	if e.MessageID == uuid.Nil || e.ConversationID == uuid.Nil || e.ClientTS.IsZero() ||
		len(e.Payload) > MaxPayloadBytes || len(recipients) == 0 || len(recipients) > MaxRecipients {
		return Envelope{}, ErrInvalidEnvelope
	}
	seen := make(map[uuid.UUID]bool, len(recipients))
	for _, r := range recipients {
		if r == uuid.Nil || seen[r] {
			return Envelope{}, ErrInvalidEnvelope
		}
		if r == sender {
			return Envelope{}, ErrSelfRecipient
		}
		seen[r] = true
	}
	e.SenderID = sender
	e.RecipientID = uuid.Nil
	e.Kind = KindEnvelope
	e.RefMessageID = uuid.NullUUID{}
	e.ServerTS = s.now().UTC()
	if e.Payload == nil {
		e.Payload = []byte{}
	}
	stored, dup, err := s.store.AcceptMulti(ctx, e, recipients)
	if err != nil {
		return Envelope{}, err
	}
	if !dup {
		for _, r := range recipients {
			s.deliver(ctx, r)
		}
	}
	return stored, nil
}

// Ack records that recipient durably stored envelope seq, and tells the
// original sender it was delivered.
func (s *Service) Ack(ctx context.Context, recipient uuid.UUID, seq int64) error {
	receipt, err := s.store.Ack(ctx, recipient, seq, s.now().UTC())
	if err != nil {
		return err
	}
	if receipt != nil {
		s.deliver(ctx, receipt.RecipientID)
	}
	return nil
}

func (s *Service) Pending(ctx context.Context, recipient uuid.UUID, after int64, limit int) ([]Envelope, error) {
	return s.store.Pending(ctx, recipient, after, limit)
}

// deliver wakes the recipient's live connection or, if offline, sends a
// content-free push (rate-limited per user).
func (s *Service) deliver(ctx context.Context, recipient uuid.UUID) {
	if s.hub.Notify(recipient) {
		return
	}
	s.wakeMu.Lock()
	now := s.now()
	if last, ok := s.lastWake[recipient]; ok && now.Sub(last) < wakeInterval {
		s.wakeMu.Unlock()
		return
	}
	s.lastWake[recipient] = now
	s.wakeMu.Unlock()
	go func() {
		// Detached from the request: the push should not be cancelled when
		// the sender's connection closes.
		ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancel()
		if err := s.waker.Wake(ctx, recipient); err != nil {
			s.log.Warn("push wake failed", "err", err)
		}
	}()
}

const wakeInterval = 10 * time.Second

// RunJanitor purges expired envelopes and tombstones until ctx is cancelled.
func (s *Service) RunJanitor(ctx context.Context, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if err := s.store.Purge(ctx, s.now().Add(-s.retention)); err != nil && ctx.Err() == nil {
				s.log.Error("messaging janitor failed", "err", err)
			}
		}
	}
}
