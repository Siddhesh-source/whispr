//go:build libsignal

package sigverify

/*
#cgo LDFLAGS: -lsignal_ffi -lstdc++ -ldl -lm -lpthread
#include <stdlib.h>
#include "signal_ffi.h"
*/
import "C"

import (
	"errors"
	"unsafe"
)

type libsignalVerifier struct{}

// New returns the libsignal-backed verifier.
func New() (Verifier, error) {
	return libsignalVerifier{}, nil
}

func (libsignalVerifier) ValidatePublicKey(b []byte) error {
	key, err := deserializePublicKey(b)
	if err != nil {
		return err
	}
	destroyPublicKey(key)
	return nil
}

func (libsignalVerifier) Verify(publicKey, message, signature []byte) (bool, error) {
	if len(message) == 0 || len(signature) == 0 {
		return false, nil
	}
	key, err := deserializePublicKey(publicKey)
	if err != nil {
		return false, err
	}
	defer destroyPublicKey(key)

	msg, msgFree := borrow(message)
	defer msgFree()
	sig, sigFree := borrow(signature)
	defer sigFree()

	var ok C.bool
	if ferr := C.signal_publickey_verify(&ok, C.SignalConstPointerPublicKey{raw: key.raw}, msg, sig); ferr != nil {
		C.signal_error_free(ferr)
		return false, nil
	}
	return bool(ok), nil
}

// borrow copies b into C memory so no Go pointers cross the FFI boundary.
func borrow(b []byte) (C.SignalBorrowedBuffer, func()) {
	p := C.CBytes(b)
	return C.SignalBorrowedBuffer{base: (*C.uint8_t)(p), length: C.size_t(len(b))}, func() { C.free(p) }
}

func deserializePublicKey(b []byte) (C.SignalMutPointerPublicKey, error) {
	var key C.SignalMutPointerPublicKey
	if len(b) != PublicKeyLen {
		return key, ErrInvalidKey
	}
	buf, free := borrow(b)
	defer free()
	if ferr := C.signal_publickey_deserialize(&key, buf); ferr != nil {
		C.signal_error_free(ferr)
		return key, ErrInvalidKey
	}
	if key.raw == nil {
		return key, ErrInvalidKey
	}
	return key, nil
}

func destroyPublicKey(key C.SignalMutPointerPublicKey) {
	if ferr := C.signal_publickey_destroy(key); ferr != nil {
		C.signal_error_free(ferr)
	}
}

// ownedBytes copies a libsignal-owned buffer into Go memory and frees it.
func ownedBytes(b C.SignalOwnedBuffer) []byte {
	out := C.GoBytes(unsafe.Pointer(b.base), C.int(b.length))
	C.signal_free_buffer(b.base, b.length)
	return out
}

var errFFI = errors.New("sigverify: libsignal call failed")
