package push

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"

	"github.com/google/uuid"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

// FCMWaker sends data-only, high-priority wake-ups through the FCM HTTP v1 API.
type FCMWaker struct {
	store    Store
	client   *http.Client // authenticated with the service account
	endpoint string       // https://fcm.googleapis.com/v1/projects/<id>/messages:send
}

// NewFCMWakerFromFile loads a Firebase service-account JSON key.
func NewFCMWakerFromFile(ctx context.Context, store Store, path string) (*FCMWaker, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read FCM credentials: %w", err)
	}
	creds, err := google.CredentialsFromJSON(ctx, data, fcmScope) //nolint:staticcheck // service-account JSON from operator config
	if err != nil {
		return nil, fmt.Errorf("parse FCM credentials: %w", err)
	}
	if creds.ProjectID == "" {
		return nil, fmt.Errorf("FCM credentials have no project_id")
	}
	return NewFCMWaker(store, oauth2.NewClient(context.WithoutCancel(ctx), creds.TokenSource), "https://fcm.googleapis.com/v1/projects/"+creds.ProjectID+"/messages:send"), nil
}

func NewFCMWaker(store Store, client *http.Client, endpoint string) *FCMWaker {
	return &FCMWaker{store: store, client: client, endpoint: endpoint}
}

type fcmRequest struct {
	Message fcmMessage `json:"message"`
}

type fcmMessage struct {
	Token   string            `json:"token"`
	Data    map[string]string `json:"data"`
	Android fcmAndroid        `json:"android"`
}

type fcmAndroid struct {
	Priority    string `json:"priority"`
	TTL         string `json:"ttl"`
	CollapseKey string `json:"collapse_key"`
}

// Wake sends a wake-up to the user's device. A user without a token is not an error.
func (f *FCMWaker) Wake(ctx context.Context, user uuid.UUID) error {
	_, token, err := f.store.Token(ctx, user)
	if errors.Is(err, ErrNoToken) {
		return nil
	}
	if err != nil {
		return err
	}
	body, _ := json.Marshal(fcmRequest{Message: fcmMessage{
		Token: token,
		// The only field. No content, sender, or conversation, ever.
		Data:    map[string]string{"t": "wake"},
		Android: fcmAndroid{Priority: "HIGH", TTL: "3600s", CollapseKey: "wake"},
	}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	switch {
	case resp.StatusCode < 300:
		return nil
	case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone:
		// Token unregistered (app uninstalled or token rotated).
		return f.store.DeleteToken(ctx, user, token)
	default:
		return fmt.Errorf("fcm: status %d", resp.StatusCode)
	}
}
