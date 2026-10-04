package health

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

type pinger struct{ err error }

func (p pinger) Ping(context.Context) error { return p.err }

func TestHealth(t *testing.T) {
	for _, tc := range []struct {
		err  error
		want int
	}{{nil, http.StatusOK}, {errors.New("down"), http.StatusServiceUnavailable}} {
		rec := httptest.NewRecorder()
		Handler(pinger{tc.err})(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
		if rec.Code != tc.want {
			t.Errorf("err=%v: status %d, want %d", tc.err, rec.Code, tc.want)
		}
	}
}
