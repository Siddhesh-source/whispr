package attachments

import (
	"bytes"
	"context"
	"errors"
	"io"
	"sync"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// ErrNotFound means the object (or its row) does not exist, or has expired.
var ErrNotFound = errors.New("attachments: not found")

// Objects is the blob store. Everything put here is client-side ciphertext.
type Objects interface {
	Put(ctx context.Context, key string, r io.Reader, size int64) error
	// Get returns the object and its size. ErrNotFound if it doesn't exist.
	Get(ctx context.Context, key string) (io.ReadCloser, int64, error)
	// Delete removes the object. Deleting a missing object is not an error.
	Delete(ctx context.Context, key string) error
}

// S3Config names an S3-compatible bucket (MinIO in development).
type S3Config struct {
	Endpoint  string // host:port, no scheme
	AccessKey string
	SecretKey string
	Bucket    string
	UseSSL    bool
}

type S3Objects struct {
	client *minio.Client
	bucket string
}

func NewS3Objects(c S3Config) (*S3Objects, error) {
	client, err := minio.New(c.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(c.AccessKey, c.SecretKey, ""),
		Secure: c.UseSSL,
	})
	if err != nil {
		return nil, err
	}
	return &S3Objects{client: client, bucket: c.Bucket}, nil
}

// CheckBucket fails if the bucket is missing or unreachable, so a
// misconfigured server says so at startup rather than on the first upload.
func (s *S3Objects) CheckBucket(ctx context.Context) error {
	ok, err := s.client.BucketExists(ctx, s.bucket)
	if err != nil {
		return err
	}
	if !ok {
		return errors.New("attachments: bucket " + s.bucket + " does not exist")
	}
	return nil
}

func (s *S3Objects) Put(ctx context.Context, key string, r io.Reader, size int64) error {
	_, err := s.client.PutObject(ctx, s.bucket, key, r, size, minio.PutObjectOptions{
		ContentType: "application/octet-stream",
	})
	return err
}

func (s *S3Objects) Get(ctx context.Context, key string) (io.ReadCloser, int64, error) {
	obj, err := s.client.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, 0, err
	}
	info, err := obj.Stat()
	if err != nil {
		_ = obj.Close()
		if minio.ToErrorResponse(err).Code == minio.NoSuchKey {
			return nil, 0, ErrNotFound
		}
		return nil, 0, err
	}
	return obj, info.Size, nil
}

func (s *S3Objects) Delete(ctx context.Context, key string) error {
	return s.client.RemoveObject(ctx, s.bucket, key, minio.RemoveObjectOptions{})
}

// MemObjects is an in-memory Objects for tests.
type MemObjects struct {
	mu   sync.Mutex
	data map[string][]byte
}

func NewMemObjects() *MemObjects { return &MemObjects{data: map[string][]byte{}} }

func (m *MemObjects) Put(_ context.Context, key string, r io.Reader, size int64) error {
	b, err := io.ReadAll(io.LimitReader(r, size+1))
	if err != nil {
		return err
	}
	if int64(len(b)) != size {
		return errors.New("attachments: size mismatch")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.data[key] = b
	return nil
}

func (m *MemObjects) Get(_ context.Context, key string) (io.ReadCloser, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	b, ok := m.data[key]
	if !ok {
		return nil, 0, ErrNotFound
	}
	return io.NopCloser(bytes.NewReader(b)), int64(len(b)), nil
}

func (m *MemObjects) Delete(_ context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.data, key)
	return nil
}

// Snapshot returns a copy of every stored object (tests inspect raw blobs).
func (m *MemObjects) Snapshot() map[string][]byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make(map[string][]byte, len(m.data))
	for k, v := range m.data {
		out[k] = bytes.Clone(v)
	}
	return out
}
