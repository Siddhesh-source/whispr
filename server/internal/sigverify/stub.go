//go:build !libsignal

package sigverify

// New always fails in builds without libsignal, so a misbuilt server cannot
// run with a fake or missing verifier.
func New() (Verifier, error) {
	return nil, ErrUnavailable
}
