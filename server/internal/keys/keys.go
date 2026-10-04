// Package keys stores and hands out users' public prekeys for libsignal
// session setup (PQXDH).
//
// The server is crypto-free: it never sees private keys or sessions. It only
// checks that what it stores is well formed and signed by the owner's
// identity key (via libsignal, through sigverify), and it hands out each
// one-time prekey at most once.
package keys

import (
	"errors"
	"fmt"

	"whispr/server/internal/sigverify"
)

// Serialized sizes from libsignal 0.104 (checked against the client
// library): EC public keys carry a 0x05 type byte, Kyber1024 public keys a
// 0x08 type byte, and XEdDSA signatures are 64 bytes.
const (
	ECKeyLen     = sigverify.PublicKeyLen
	KyberKeyLen  = 1569
	SignatureLen = 64
	kyberType    = 0x08

	// MaxKeyID matches the 24-bit ID space libsignal clients use.
	MaxKeyID = 1<<24 - 1
	// MaxBatch bounds each kind of one-time key per upload.
	MaxBatch = 100
	// MaxStored bounds each kind of one-time key held per user.
	MaxStored = 200

	minRegistrationID = 1
	maxRegistrationID = 16380
)

var (
	// ErrInvalid means a key, signature or ID is malformed or not signed by
	// the uploader's identity key.
	ErrInvalid = errors.New("keys: invalid upload")
	// ErrConflict means an ID already holds different key bytes (or a
	// different registration ID). Re-uploading identical bytes is a no-op.
	ErrConflict = errors.New("keys: key id conflict")
	// ErrTooMany means the upload would exceed MaxStored one-time keys.
	ErrTooMany = errors.New("keys: too many keys")
	// ErrNoKeys means the user has not uploaded a complete key set yet.
	ErrNoKeys = errors.New("keys: user has no keys")
	// ErrUnknownUser means no such user.
	ErrUnknownUser = errors.New("keys: unknown user")
)

// SignedKey is a prekey signed by its owner's identity key: the signed EC
// prekey, the last-resort Kyber prekey, or a one-time Kyber prekey.
type SignedKey struct {
	KeyID     int32
	PublicKey []byte
	Signature []byte
}

// OneTimeKey is a one-time EC prekey (unsigned, as in libsignal).
type OneTimeKey struct {
	KeyID     int32
	PublicKey []byte
}

// Upload is everything a client may send in one request. Every part is
// optional so the client can register, top up or rotate independently.
type Upload struct {
	RegistrationID *int32
	SignedPreKey   *SignedKey
	LastResort     *SignedKey
	OneTime        []OneTimeKey
	Kyber          []SignedKey
}

// Counts is what a client needs to decide whether to top up or rotate.
type Counts struct {
	RegistrationID  *int32
	SignedPreKeyID  *int32
	LastResortKeyID *int32
	OneTime         int
	Kyber           int
}

// Bundle is one prekey bundle for starting a session with a user.
type Bundle struct {
	RegistrationID  int32
	IdentityKey     []byte
	SignedPreKey    SignedKey
	OneTime         *OneTimeKey // nil when the user has none left
	Kyber           SignedKey
	KyberLastResort bool
}

// Validate checks shapes and signatures against the uploader's identity key.
func Validate(u Upload, identityKey []byte, v sigverify.Verifier) error {
	if u.RegistrationID != nil && (*u.RegistrationID < minRegistrationID || *u.RegistrationID > maxRegistrationID) {
		return fmt.Errorf("%w: registration id", ErrInvalid)
	}
	if len(u.OneTime) > MaxBatch || len(u.Kyber) > MaxBatch {
		return fmt.Errorf("%w: batch too large", ErrInvalid)
	}
	if u.SignedPreKey != nil {
		if err := validateECKey(u.SignedPreKey.KeyID, u.SignedPreKey.PublicKey, v); err != nil {
			return err
		}
		if err := verify(identityKey, *u.SignedPreKey, v); err != nil {
			return err
		}
	}
	if u.LastResort != nil {
		if err := validateKyber(*u.LastResort, identityKey, v); err != nil {
			return err
		}
	}
	seen := map[int32]bool{}
	for _, k := range u.OneTime {
		if seen[k.KeyID] {
			return fmt.Errorf("%w: duplicate one-time key id", ErrInvalid)
		}
		seen[k.KeyID] = true
		if err := validateECKey(k.KeyID, k.PublicKey, v); err != nil {
			return err
		}
	}
	seen = map[int32]bool{}
	for _, k := range u.Kyber {
		if seen[k.KeyID] {
			return fmt.Errorf("%w: duplicate kyber key id", ErrInvalid)
		}
		seen[k.KeyID] = true
		if err := validateKyber(k, identityKey, v); err != nil {
			return err
		}
	}
	return nil
}

func validID(id int32) bool { return id >= 0 && id <= MaxKeyID }

func validateECKey(id int32, pub []byte, v sigverify.Verifier) error {
	if !validID(id) {
		return fmt.Errorf("%w: key id", ErrInvalid)
	}
	if v.ValidatePublicKey(pub) != nil {
		return fmt.Errorf("%w: ec public key", ErrInvalid)
	}
	return nil
}

func validateKyber(k SignedKey, identityKey []byte, v sigverify.Verifier) error {
	if !validID(k.KeyID) {
		return fmt.Errorf("%w: key id", ErrInvalid)
	}
	if len(k.PublicKey) != KyberKeyLen || k.PublicKey[0] != kyberType {
		return fmt.Errorf("%w: kyber public key", ErrInvalid)
	}
	return verify(identityKey, k, v)
}

// verify checks the identity key's signature over the serialized public key,
// which is what libsignal signs for signed and Kyber prekeys.
func verify(identityKey []byte, k SignedKey, v sigverify.Verifier) error {
	if len(k.Signature) != SignatureLen {
		return fmt.Errorf("%w: signature length", ErrInvalid)
	}
	ok, err := v.Verify(identityKey, k.PublicKey, k.Signature)
	if err != nil || !ok {
		return fmt.Errorf("%w: signature", ErrInvalid)
	}
	return nil
}
