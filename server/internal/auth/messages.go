package auth

import "github.com/google/uuid"

// Signed-message formats. These are a wire contract with the Android client
// (see docs/ARCHITECTURE.md, "Auth protocol"); change them only by bumping
// the version label. The labels give domain separation so a signature made
// for one purpose can never be replayed as another.
const (
	registerLabel = "whispr-register-v1\x00"
	authLabel     = "whispr-auth-v1\x00"
)

// NonceLen is the size of an auth challenge nonce in bytes.
const NonceLen = 32

// RegisterMessage is what the client signs to register:
//
//	"whispr-register-v1" 0x00 || identity_key (33 bytes) || display_name (UTF-8)
//
// The identity key has a fixed length, so the encoding is unambiguous.
func RegisterMessage(identityKey []byte, displayName string) []byte {
	m := make([]byte, 0, len(registerLabel)+len(identityKey)+len(displayName))
	m = append(m, registerLabel...)
	m = append(m, identityKey...)
	return append(m, displayName...)
}

// AuthMessage is what the client signs to answer a challenge:
//
//	"whispr-auth-v1" 0x00 || user_id (16 raw bytes) || nonce (32 bytes)
func AuthMessage(userID uuid.UUID, nonce []byte) []byte {
	m := make([]byte, 0, len(authLabel)+16+len(nonce))
	m = append(m, authLabel...)
	m = append(m, userID[:]...)
	return append(m, nonce...)
}
