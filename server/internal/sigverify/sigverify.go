// Package sigverify verifies libsignal identity-key signatures.
//
// The only production implementation calls libsignal's C FFI (build tag
// "libsignal"). The server contains no signature-scheme logic of its own.
// A binary built without the tag cannot construct a Verifier and refuses to
// start.
package sigverify

import "errors"

var (
	// ErrUnavailable is returned by New when the binary was built without libsignal.
	ErrUnavailable = errors.New("sigverify: built without libsignal (use -tags libsignal)")
	// ErrInvalidKey means the bytes are not a serialized libsignal public key.
	ErrInvalidKey = errors.New("sigverify: invalid public key")
)

// PublicKeyLen is the length of a serialized libsignal (Curve25519) public key,
// including its one-byte type prefix.
const PublicKeyLen = 33

type Verifier interface {
	// ValidatePublicKey returns ErrInvalidKey unless b is a well-formed
	// serialized libsignal public key.
	ValidatePublicKey(b []byte) error
	// Verify reports whether signature is a valid signature by publicKey over
	// message. A malformed key returns ErrInvalidKey.
	Verify(publicKey, message, signature []byte) (bool, error)
}
