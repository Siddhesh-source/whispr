// Package sigverifytest provides a deterministic stand-in Verifier for unit
// tests that cannot link libsignal. It is NOT a signature scheme: anyone who
// knows the public key can "sign". Never wire it into cmd/.
package sigverifytest

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"

	"whispr/server/internal/sigverify"
)

// Fake accepts signature == HMAC-SHA256(publicKey, message).
type Fake struct{}

var _ sigverify.Verifier = Fake{}

func (Fake) ValidatePublicKey(b []byte) error {
	if len(b) != sigverify.PublicKeyLen || b[0] != 0x05 {
		return sigverify.ErrInvalidKey
	}
	return nil
}

func (f Fake) Verify(publicKey, message, signature []byte) (bool, error) {
	if err := f.ValidatePublicKey(publicKey); err != nil {
		return false, err
	}
	return hmac.Equal(Sign(publicKey, message), signature), nil
}

// NewPublicKey returns a random key in libsignal's serialized shape.
func NewPublicKey() []byte {
	k := make([]byte, sigverify.PublicKeyLen)
	_, _ = rand.Read(k[1:])
	k[0] = 0x05
	return k
}

// Sign produces a signature Fake will accept.
func Sign(publicKey, message []byte) []byte {
	m := hmac.New(sha256.New, publicKey)
	m.Write(message)
	return m.Sum(nil)
}
