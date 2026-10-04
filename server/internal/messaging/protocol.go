package messaging

import (
	"time"

	"github.com/google/uuid"
)

// WebSocket protocol: JSON text frames. []byte fields are standard base64.
// The payload is never parsed by the server.
//
// Client → server:
//
//	{"type":"send", "id", "conversation_id", "recipient_id", "client_ts", "payload"}
//	{"type":"ack", "seq"}                    after the envelope is durably stored on the device
//	{"type":"transient", "recipient_id", "conversation_id", "payload"}
//	                                          best effort, never stored (typing indicators)
//
// Server → client:
//
//	{"type":"accepted", "id", "seq", "server_ts"}  envelope is durably stored ("sent")
//	{"type":"rejected", "id", "code"}
//	{"type":"envelope", "seq", "id", "conversation_id", "sender_id", "kind", "ref_id", "client_ts", "server_ts", "payload"}
//	{"type":"transient", "sender_id", "conversation_id", "payload"}
//
// Heartbeat uses WebSocket ping/pong control frames in both directions.

const (
	frameSend      = "send"
	frameAck       = "ack"
	frameTransient = "transient"
	frameAccepted  = "accepted"
	frameRejected  = "rejected"
	frameEnvelope  = "envelope"
)

const (
	kindNameMessage   = "envelope"
	kindNameDelivered = "delivered"
)

const (
	codeInvalid          = "invalid"
	codeUnknownRecipient = "unknown_recipient"
	codeSelf             = "self"
	codeRateLimited      = "rate_limited"
	codeInternal         = "internal"
)

type clientFrame struct {
	Type           string    `json:"type"`
	ID             uuid.UUID `json:"id"`
	ConversationID uuid.UUID `json:"conversation_id"`
	RecipientID    uuid.UUID `json:"recipient_id"`
	ClientTS       time.Time `json:"client_ts"`
	Payload        []byte    `json:"payload"`
	Seq            int64     `json:"seq"`
}

type acceptedFrame struct {
	Type     string    `json:"type"`
	ID       uuid.UUID `json:"id"`
	Seq      int64     `json:"seq"`
	ServerTS time.Time `json:"server_ts"`
}

type rejectedFrame struct {
	Type string    `json:"type"`
	ID   uuid.UUID `json:"id"`
	Code string    `json:"code"`
}

type envelopeFrame struct {
	Type           string     `json:"type"`
	Seq            int64      `json:"seq"`
	ID             uuid.UUID  `json:"id"`
	ConversationID uuid.UUID  `json:"conversation_id"`
	SenderID       uuid.UUID  `json:"sender_id"`
	Kind           string     `json:"kind"`
	RefID          *uuid.UUID `json:"ref_id,omitempty"`
	ClientTS       time.Time  `json:"client_ts"`
	ServerTS       time.Time  `json:"server_ts"`
	Payload        []byte     `json:"payload"`
}

type transientFrame struct {
	Type           string    `json:"type"`
	SenderID       uuid.UUID `json:"sender_id"`
	ConversationID uuid.UUID `json:"conversation_id"`
	Payload        []byte    `json:"payload"`
}

func toEnvelopeFrame(e Envelope) envelopeFrame {
	f := envelopeFrame{
		Type: frameEnvelope, Seq: e.Seq, ID: e.MessageID, ConversationID: e.ConversationID,
		SenderID: e.SenderID, Kind: kindNameMessage, ClientTS: e.ClientTS.UTC(), ServerTS: e.ServerTS.UTC(),
		Payload: e.Payload,
	}
	if e.Kind == KindDelivered {
		f.Kind = kindNameDelivered
	}
	if e.RefMessageID.Valid {
		ref := e.RefMessageID.UUID
		f.RefID = &ref
	}
	return f
}
