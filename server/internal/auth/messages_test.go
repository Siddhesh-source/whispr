package auth

import (
	"encoding/hex"
	"testing"

	"github.com/google/uuid"
)

// Shared vectors with android/data/.../AuthMessagesTest.kt. If these change,
// the wire format changed: bump the label version on both sides.
const (
	registerVector = "7768697370722d72656769737465722d763100050102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20416461"
	authVector     = "7768697370722d617574682d76310000112233445566778899aabbccddeeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
)

func TestMessageVectors(t *testing.T) {
	key := make([]byte, 33)
	key[0] = 0x05
	for i := 1; i < 33; i++ {
		key[i] = byte(i)
	}
	if got := hex.EncodeToString(RegisterMessage(key, "Ada")); got != registerVector {
		t.Errorf("RegisterMessage = %s", got)
	}

	nonce := make([]byte, NonceLen)
	for i := range nonce {
		nonce[i] = byte(i)
	}
	id := uuid.MustParse("00112233-4455-6677-8899-aabbccddeeff")
	if got := hex.EncodeToString(AuthMessage(id, nonce)); got != authVector {
		t.Errorf("AuthMessage = %s", got)
	}
}
