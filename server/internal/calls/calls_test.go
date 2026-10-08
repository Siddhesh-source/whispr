package calls

import (
	"context"
	"crypto/hmac"
	"crypto/sha1" //nolint:gosec // coturn's scheme
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

var user = uuid.MustParse("00000000-0000-0000-0000-0000000000aa")

func signedIn(context.Context) (uuid.UUID, bool) { return user, true }

func serve(m *Module) http.Handler {
	r := chi.NewRouter()
	m.Routes(r)
	return r
}

func get(h http.Handler) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/calls/turn", nil))
	return rec
}

func TestCredentialsMatchCoturnScheme(t *testing.T) {
	expiry := time.Unix(1_800_000_000, 0)
	name, password := Credentials("s3cret", user, expiry)
	if name != "1800000000:"+user.String() {
		t.Fatalf("username %q", name)
	}
	mac := hmac.New(sha1.New, []byte("s3cret"))
	mac.Write([]byte(name))
	if want := base64.StdEncoding.EncodeToString(mac.Sum(nil)); password != want {
		t.Fatalf("password %q, want %q", password, want)
	}
	// A different secret gives a different password.
	if _, other := Credentials("other", user, expiry); other == password {
		t.Fatal("password does not depend on the secret")
	}
}

func TestTurnReturnsShortLivedCredentials(t *testing.T) {
	m := NewModule(Options{
		Secret: strings.Repeat("k", 32),
		URLs:   []string{"turn:relay.example:3478?transport=udp", "turn:relay.example:3478?transport=tcp"},
		TTL:    10 * time.Minute,
	}, signedIn)
	now := time.Unix(1_700_000_000, 0)
	m.now = func() time.Time { return now }
	rec := get(serve(m))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	var body turnResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.URLs) != 2 || body.TTL != 600 {
		t.Fatalf("unexpected body %+v", body)
	}
	expiry, err := strconv.ParseInt(strings.Split(body.Username, ":")[0], 10, 64)
	if err != nil || expiry != now.Add(10*time.Minute).Unix() {
		t.Fatalf("expiry %d (%v)", expiry, err)
	}
	if !strings.HasSuffix(body.Username, ":"+user.String()) {
		t.Fatalf("username %q does not name the user", body.Username)
	}
	_, want := Credentials(strings.Repeat("k", 32), user, now.Add(10*time.Minute))
	if body.Credential != want {
		t.Fatal("credential does not verify")
	}
}

func TestTurnUnconfiguredAnswers503(t *testing.T) {
	rec := get(serve(NewModule(Options{}, signedIn)))
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "calls_unavailable") {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
}

func TestTurnIsRateLimitedPerUser(t *testing.T) {
	h := serve(NewModule(Options{Secret: strings.Repeat("k", 32), URLs: []string{"turn:x"}, PerMinute: 2}, signedIn))
	for i := 0; i < 2; i++ {
		if rec := get(h); rec.Code != http.StatusOK {
			t.Fatalf("request %d: %d", i, rec.Code)
		}
	}
	if rec := get(h); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("third request: %d", rec.Code)
	}
}

func TestTurnNeedsAUser(t *testing.T) {
	m := NewModule(Options{Secret: strings.Repeat("k", 32), URLs: []string{"turn:x"}},
		func(context.Context) (uuid.UUID, bool) { return uuid.Nil, false })
	if rec := get(serve(m)); rec.Code != http.StatusUnauthorized {
		t.Fatalf("status %d", rec.Code)
	}
}
