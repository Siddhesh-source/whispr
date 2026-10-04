//go:build libsignal

package sigverify

import (
	"bytes"
	"testing"
)

func TestLibsignalRoundTrip(t *testing.T) {
	v, err := New()
	if err != nil {
		t.Fatal(err)
	}
	kp, err := NewTestKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	defer kp.Close()

	if len(kp.Public) != PublicKeyLen {
		t.Fatalf("public key length = %d", len(kp.Public))
	}
	if err := v.ValidatePublicKey(kp.Public); err != nil {
		t.Fatalf("ValidatePublicKey: %v", err)
	}

	msg := []byte("whispr-auth-v1 test message")
	sig, err := kp.Sign(msg)
	if err != nil {
		t.Fatal(err)
	}
	if ok, err := v.Verify(kp.Public, msg, sig); err != nil || !ok {
		t.Fatalf("valid signature rejected: ok=%v err=%v", ok, err)
	}

	tampered := bytes.Clone(msg)
	tampered[0] ^= 1
	if ok, _ := v.Verify(kp.Public, tampered, sig); ok {
		t.Fatal("signature over different message accepted")
	}

	other, err := NewTestKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	if ok, _ := v.Verify(other.Public, msg, sig); ok {
		t.Fatal("signature accepted under wrong key")
	}
}

func TestLibsignalRejectsMalformedKeys(t *testing.T) {
	v, _ := New()
	bad := [][]byte{nil, make([]byte, 32), append([]byte{0x99}, make([]byte, 32)...)}
	for _, k := range bad {
		if err := v.ValidatePublicKey(k); err == nil {
			t.Errorf("accepted malformed key %x", k)
		}
	}
}
