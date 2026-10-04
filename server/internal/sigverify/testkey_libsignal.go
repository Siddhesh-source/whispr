//go:build libsignal

package sigverify

/*
#include <stdlib.h>
#include "signal_ffi.h"
*/
import "C"

// TestKeyPair is a libsignal private key for integration tests only. It
// lives in a non-test file because cgo is not allowed in _test.go files.
type TestKeyPair struct {
	priv C.SignalMutPointerPrivateKey
	// Public is the serialized public key.
	Public []byte
}

// NewTestKeyPair generates a fresh key pair via libsignal. Callers must Close it.
func NewTestKeyPair() (*TestKeyPair, error) {
	var priv C.SignalMutPointerPrivateKey
	if ferr := C.signal_privatekey_generate(&priv); ferr != nil {
		C.signal_error_free(ferr)
		return nil, errFFI
	}
	var pub C.SignalMutPointerPublicKey
	if ferr := C.signal_privatekey_get_public_key(&pub, C.SignalConstPointerPrivateKey{raw: priv.raw}); ferr != nil {
		C.signal_error_free(ferr)
		C.signal_privatekey_destroy(priv)
		return nil, errFFI
	}
	defer destroyPublicKey(pub)
	var out C.SignalOwnedBuffer
	if ferr := C.signal_publickey_serialize(&out, C.SignalConstPointerPublicKey{raw: pub.raw}); ferr != nil {
		C.signal_error_free(ferr)
		C.signal_privatekey_destroy(priv)
		return nil, errFFI
	}
	return &TestKeyPair{priv: priv, Public: ownedBytes(out)}, nil
}

// Sign signs message with libsignal's identity-key signature scheme.
func (k *TestKeyPair) Sign(message []byte) ([]byte, error) {
	msg, free := borrow(message)
	defer free()
	var out C.SignalOwnedBuffer
	if ferr := C.signal_privatekey_sign(&out, C.SignalConstPointerPrivateKey{raw: k.priv.raw}, msg); ferr != nil {
		C.signal_error_free(ferr)
		return nil, errFFI
	}
	return ownedBytes(out), nil
}

func (k *TestKeyPair) Close() {
	if ferr := C.signal_privatekey_destroy(k.priv); ferr != nil {
		C.signal_error_free(ferr)
	}
}
