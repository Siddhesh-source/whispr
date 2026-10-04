package keys

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"whispr/server/internal/platform/httpx"
	"whispr/server/internal/sigverify"
)

// MaxUploadBytes bounds a key upload: 100 Kyber keys are about 220 KB of
// base64 JSON, plus 100 small EC keys.
const MaxUploadBytes = 512 << 10

// Store is satisfied by PGStore.
type Store interface {
	IdentityKey(ctx context.Context, user uuid.UUID) ([]byte, error)
	Upload(ctx context.Context, user uuid.UUID, u Upload, now time.Time) error
	Counts(ctx context.Context, user uuid.UUID) (Counts, error)
	Bundle(ctx context.Context, target uuid.UUID) (Bundle, error)
}

// Limits bound bundle fetches: each fetch consumes one of the target's
// one-time keys, so a per-pair limit stops one account draining one person.
type Limits struct {
	PerUser *httpx.KeyedLimiter
	PerPair *httpx.KeyedLimiter
}

// DefaultLimits: 20 bundles a minute per requester, 6 an hour per
// (requester, target) pair.
func DefaultLimits() Limits {
	return Limits{
		PerUser: httpx.NewKeyedLimiter(20, time.Minute),
		PerPair: httpx.NewKeyedLimiter(6, time.Hour),
	}
}

type Module struct {
	store    Store
	verifier sigverify.Verifier
	log      *slog.Logger
	userID   func(context.Context) (uuid.UUID, bool)
	limits   Limits
	now      func() time.Time
}

func NewModule(store Store, verifier sigverify.Verifier, log *slog.Logger, userID func(context.Context) (uuid.UUID, bool), limits Limits) *Module {
	return &Module{store: store, verifier: verifier, log: log, userID: userID, limits: limits, now: time.Now}
}

// Routes mounts authenticated key endpoints.
func (m *Module) Routes(r chi.Router) {
	r.Put("/keys", m.upload)
	r.Get("/keys/count", m.count)
	r.Get("/keys/{id}", m.bundle)
}

type signedKeyJSON struct {
	KeyID     int32  `json:"key_id"`
	PublicKey []byte `json:"public_key"`
	Signature []byte `json:"signature"`
}

type oneTimeKeyJSON struct {
	KeyID     int32  `json:"key_id"`
	PublicKey []byte `json:"public_key"`
}

type uploadRequest struct {
	RegistrationID *int32           `json:"registration_id,omitempty"`
	SignedPreKey   *signedKeyJSON   `json:"signed_prekey,omitempty"`
	LastResort     *signedKeyJSON   `json:"kyber_last_resort,omitempty"`
	OneTime        []oneTimeKeyJSON `json:"one_time_prekeys,omitempty"`
	Kyber          []signedKeyJSON  `json:"kyber_prekeys,omitempty"`
}

type countsResponse struct {
	RegistrationID  *int32 `json:"registration_id"`
	SignedPreKeyID  *int32 `json:"signed_prekey_id"`
	LastResortKeyID *int32 `json:"kyber_last_resort_id"`
	OneTime         int    `json:"one_time_prekeys"`
	Kyber           int    `json:"kyber_prekeys"`
}

type kyberKeyJSON struct {
	signedKeyJSON
	LastResort bool `json:"last_resort"`
}

type bundleResponse struct {
	UserID         uuid.UUID       `json:"user_id"`
	DeviceID       int             `json:"device_id"`
	RegistrationID int32           `json:"registration_id"`
	IdentityKey    []byte          `json:"identity_key"`
	SignedPreKey   signedKeyJSON   `json:"signed_prekey"`
	OneTime        *oneTimeKeyJSON `json:"one_time_prekey,omitempty"`
	Kyber          kyberKeyJSON    `json:"kyber_prekey"`
}

// deviceID is fixed: one device per account.
const deviceID = 1

func (m *Module) upload(w http.ResponseWriter, r *http.Request) {
	user, _ := m.userID(r.Context())
	var req uploadRequest
	if err := httpx.DecodeJSONLimit(w, r, &req, MaxUploadBytes); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed request")
		return
	}
	u := req.toUpload()
	identityKey, err := m.store.IdentityKey(r.Context(), user)
	if err != nil {
		m.internal(w, err)
		return
	}
	if err := Validate(u, identityKey, m.verifier); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_keys", "invalid keys")
		return
	}
	switch err := m.store.Upload(r.Context(), user, u, m.now().UTC()); {
	case err == nil:
		w.WriteHeader(http.StatusNoContent)
	case errors.Is(err, ErrConflict):
		httpx.WriteError(w, http.StatusConflict, "key_id_conflict", "a different key already uses this id")
	case errors.Is(err, ErrTooMany):
		httpx.WriteError(w, http.StatusBadRequest, "too_many_keys", "too many one-time keys stored")
	default:
		m.internal(w, err)
	}
}

func (m *Module) count(w http.ResponseWriter, r *http.Request) {
	user, _ := m.userID(r.Context())
	c, err := m.store.Counts(r.Context(), user)
	if err != nil {
		m.internal(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, countsResponse(c))
}

func (m *Module) bundle(w http.ResponseWriter, r *http.Request) {
	requester, _ := m.userID(r.Context())
	target, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "malformed user id")
		return
	}
	if target == requester {
		httpx.WriteError(w, http.StatusBadRequest, "self", "cannot fetch your own bundle")
		return
	}
	// Check the pair limit only after the per-user limit passed, so a
	// throttled requester doesn't also burn pair tokens.
	if !m.limits.PerUser.Allow(requester.String()) || !m.limits.PerPair.Allow(requester.String()+"/"+target.String()) {
		httpx.WriteRateLimited(w, time.Minute)
		return
	}
	b, err := m.store.Bundle(r.Context(), target)
	switch {
	case errors.Is(err, ErrUnknownUser):
		httpx.WriteError(w, http.StatusNotFound, "unknown_user", "unknown user")
		return
	case errors.Is(err, ErrNoKeys):
		httpx.WriteError(w, http.StatusNotFound, "no_keys", "user has no keys yet")
		return
	case err != nil:
		m.internal(w, err)
		return
	}
	resp := bundleResponse{
		UserID: target, DeviceID: deviceID, RegistrationID: b.RegistrationID, IdentityKey: b.IdentityKey,
		SignedPreKey: toJSON(b.SignedPreKey),
		Kyber:        kyberKeyJSON{signedKeyJSON: toJSON(b.Kyber), LastResort: b.KyberLastResort},
	}
	if b.OneTime != nil {
		ot := oneTimeKeyJSON(*b.OneTime)
		resp.OneTime = &ot
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (m *Module) internal(w http.ResponseWriter, err error) {
	m.log.Error("keys handler error", "err", err)
	httpx.WriteError(w, http.StatusInternalServerError, "internal", "internal error")
}

func (r uploadRequest) toUpload() Upload {
	u := Upload{RegistrationID: r.RegistrationID}
	if r.SignedPreKey != nil {
		k := fromJSON(*r.SignedPreKey)
		u.SignedPreKey = &k
	}
	if r.LastResort != nil {
		k := fromJSON(*r.LastResort)
		u.LastResort = &k
	}
	for _, k := range r.OneTime {
		u.OneTime = append(u.OneTime, OneTimeKey(k))
	}
	for _, k := range r.Kyber {
		u.Kyber = append(u.Kyber, fromJSON(k))
	}
	return u
}

func fromJSON(k signedKeyJSON) SignedKey {
	return SignedKey(k)
}

func toJSON(k SignedKey) signedKeyJSON {
	return signedKeyJSON(k)
}
