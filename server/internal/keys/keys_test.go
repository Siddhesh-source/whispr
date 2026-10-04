package keys

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"testing"

	"whispr/server/internal/sigverify"
	"whispr/server/internal/sigverify/sigverifytest"
)

// fakeVerifier accepts 64-byte stand-in signatures (libsignal's length):
// HMAC(publicKey, message) twice. It is not a signature scheme.
type fakeVerifier struct{ sigverifytest.Fake }

func (fakeVerifier) Verify(publicKey, message, signature []byte) (bool, error) {
	if err := (sigverifytest.Fake{}).ValidatePublicKey(publicKey); err != nil {
		return false, err
	}
	return hmac.Equal(fakeSign(publicKey, message), signature), nil
}

var _ sigverify.Verifier = fakeVerifier{}

func fakeSign(publicKey, message []byte) []byte {
	m := hmac.New(sha256.New, publicKey)
	m.Write(message)
	s := m.Sum(nil)
	return append(s, s...)
}

func ecKey() []byte { return sigverifytest.NewPublicKey() }

func kyberKey() []byte {
	k := make([]byte, KyberKeyLen)
	_, _ = rand.Read(k[1:])
	k[0] = kyberType
	return k
}

func signed(identity []byte, id int32, pub []byte) SignedKey {
	return SignedKey{KeyID: id, PublicKey: pub, Signature: fakeSign(identity, pub)}
}

// fullUpload is a complete first registration: registration ID, signed
// prekey, last-resort Kyber key and n of each one-time key, IDs from first.
func fullUpload(identity []byte, first int32, n int) Upload {
	reg := int32(1234)
	sp := signed(identity, 1, ecKey())
	lr := signed(identity, 1, kyberKey())
	u := Upload{RegistrationID: &reg, SignedPreKey: &sp, LastResort: &lr}
	u.OneTime, u.Kyber = oneTimeKeys(identity, first, n)
	return u
}

func oneTimeKeys(identity []byte, first int32, n int) ([]OneTimeKey, []SignedKey) {
	var ot []OneTimeKey
	var ky []SignedKey
	for i := range n {
		id := first + int32(i)
		ot = append(ot, OneTimeKey{KeyID: id, PublicKey: ecKey()})
		ky = append(ky, signed(identity, id, kyberKey()))
	}
	return ot, ky
}

func TestValidateAcceptsWellFormedSignedUpload(t *testing.T) {
	id := ecKey()
	if err := Validate(fullUpload(id, 1, MaxBatch), id, fakeVerifier{}); err != nil {
		t.Fatal(err)
	}
}

func TestValidateRejectsMalformedOrUnsignedKeys(t *testing.T) {
	id := ecKey()
	other := ecKey()
	cases := map[string]func(u *Upload){
		"signed prekey signed by someone else": func(u *Upload) { *u.SignedPreKey = signed(other, 1, ecKey()) },
		"kyber signature over different bytes": func(u *Upload) { u.Kyber[0].PublicKey = kyberKey() },
		"last resort with bad signature":       func(u *Upload) { u.LastResort.Signature[0] ^= 1 },
		"short signature":                      func(u *Upload) { u.SignedPreKey.Signature = u.SignedPreKey.Signature[:32] },
		"ec key with wrong type byte":          func(u *Upload) { u.OneTime[0].PublicKey[0] = 0x06 },
		"ec key with wrong length":             func(u *Upload) { u.OneTime[0].PublicKey = ecKey()[:32] },
		"kyber key with wrong type byte": func(u *Upload) {
			k := kyberKey()
			k[0] = 0x07
			u.Kyber[0] = signed(id, 1, k)
		},
		"kyber key with wrong length":   func(u *Upload) { u.Kyber[0] = signed(id, 1, kyberKey()[:1568]) },
		"negative key id":               func(u *Upload) { u.OneTime[0].KeyID = -1 },
		"key id beyond 24 bits":         func(u *Upload) { u.SignedPreKey.KeyID = MaxKeyID + 1 },
		"duplicate one-time id":         func(u *Upload) { u.OneTime[1].KeyID = u.OneTime[0].KeyID },
		"duplicate kyber id":            func(u *Upload) { u.Kyber[1].KeyID = u.Kyber[0].KeyID },
		"registration id zero":          func(u *Upload) { zero := int32(0); u.RegistrationID = &zero },
		"registration id beyond 14 bit": func(u *Upload) { big := int32(16381); u.RegistrationID = &big },
		"batch too large": func(u *Upload) {
			u.OneTime, _ = oneTimeKeys(id, 1, MaxBatch+1)
		},
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			u := fullUpload(id, 1, 3)
			mutate(&u)
			if err := Validate(u, id, fakeVerifier{}); !errors.Is(err, ErrInvalid) {
				t.Fatalf("err = %v, want ErrInvalid", err)
			}
		})
	}
}
