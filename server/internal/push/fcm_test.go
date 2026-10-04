package push

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

type memStore struct {
	mu     sync.Mutex
	tokens map[uuid.UUID]string
}

func (m *memStore) SetToken(_ context.Context, u uuid.UUID, _, t string, _ time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.tokens[u] = t
	return nil
}

func (m *memStore) Token(_ context.Context, u uuid.UUID) (string, string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, ok := m.tokens[u]
	if !ok {
		return "", "", ErrNoToken
	}
	return "fcm", t, nil
}

func (m *memStore) DeleteToken(_ context.Context, u uuid.UUID, t string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.tokens[u] == t {
		delete(m.tokens, u)
	}
	return nil
}

func TestWakeSendsContentFreeDataMessage(t *testing.T) {
	var body []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ = io.ReadAll(r.Body)
	}))
	defer srv.Close()
	user := uuid.New()
	store := &memStore{tokens: map[uuid.UUID]string{user: "device-token"}}

	if err := NewFCMWaker(store, srv.Client(), srv.URL).Wake(context.Background(), user); err != nil {
		t.Fatal(err)
	}
	var got struct {
		Message struct {
			Token        string            `json:"token"`
			Data         map[string]string `json:"data"`
			Notification any               `json:"notification"`
		} `json:"message"`
	}
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatal(err)
	}
	if got.Message.Token != "device-token" {
		t.Fatalf("token %q", got.Message.Token)
	}
	if len(got.Message.Data) != 1 || got.Message.Data["t"] != "wake" || got.Message.Notification != nil {
		t.Fatalf("push must be a bare wake-up, got %s", body)
	}
}

func TestWakeWithoutTokenIsNoop(t *testing.T) {
	called := false
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { called = true }))
	defer srv.Close()
	if err := NewFCMWaker(&memStore{tokens: map[uuid.UUID]string{}}, srv.Client(), srv.URL).Wake(context.Background(), uuid.New()); err != nil {
		t.Fatal(err)
	}
	if called {
		t.Fatal("FCM called for a user without a token")
	}
}

func TestUnregisteredTokenIsDeletedButNotAReplacement(t *testing.T) {
	user := uuid.New()
	store := &memStore{tokens: map[uuid.UUID]string{user: "old"}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		// The device re-registers while FCM is answering for the old token.
		_ = store.SetToken(context.Background(), user, "fcm", "new", time.Now())
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()
	if err := NewFCMWaker(store, srv.Client(), srv.URL).Wake(context.Background(), user); err != nil {
		t.Fatal(err)
	}
	if _, tok, err := store.Token(context.Background(), user); err != nil || tok != "new" {
		t.Fatalf("fresh token was removed: %q %v", tok, err)
	}
}
