//go:build libsignal

package keys

import (
	"errors"
	"testing"

	"whispr/server/internal/sigverify"
)

// TestValidateWithRealLibsignalSignatures signs prekeys the way the Android
// client does (identity private key over the serialized public key) and
// checks the production verifier accepts them and rejects tampering.
func TestValidateWithRealLibsignalSignatures(t *testing.T) {
	v, err := sigverify.New()
	if err != nil {
		t.Fatal(err)
	}
	identity, err := sigverify.NewTestKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	defer identity.Close()
	prekey, err := sigverify.NewTestKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	defer prekey.Close()

	sign := func(pub []byte) SignedKey {
		sig, err := identity.Sign(pub)
		if err != nil {
			t.Fatal(err)
		}
		return SignedKey{KeyID: 1, PublicKey: pub, Signature: sig}
	}
	sp := sign(prekey.Public)
	lr := sign(kyberKey())
	u := Upload{SignedPreKey: &sp, LastResort: &lr, OneTime: []OneTimeKey{{KeyID: 1, PublicKey: prekey.Public}}}
	if err := Validate(u, identity.Public, v); err != nil {
		t.Fatalf("real signatures rejected: %v", err)
	}

	lr.Signature[10] ^= 1
	if err := Validate(u, identity.Public, v); !errors.Is(err, ErrInvalid) {
		t.Fatalf("tampered signature: err = %v", err)
	}
}
