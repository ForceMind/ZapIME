// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"archive/tar"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/smithy-go"
)

func TestS3Configuration(t *testing.T) {
	for _, endpoint := range []string{"http://example.com", "https://user:password@example.com", "https://example.com/?token=secret", "file:///tmp/data"} {
		if _, err := NewS3Objects(endpoint, "bucket", "key", "secret", "auto", false); err == nil {
			t.Fatal("unsafe endpoint accepted")
		}
	}
	t.Setenv("ZAPIME_OBJECT_BACKEND", "")
	t.Setenv("ZAPIME_S3_BUCKET", "configured")
	if _, err := ObjectsFromEnv(); err == nil {
		t.Fatal("partial storage settings silently ignored")
	}
}
func TestS3TransportIntegrityAndConditionalWrite(t *testing.T) {
	change := Change{id(t), id(t), 1, operation(t)}
	body, _ := json.Marshal(change)
	s := &Store{}
	// Derive the same content-addressed key through a recording transport.
	memory := &testObjects{data: map[string][]byte{}}
	s.Objects = memory
	raw, err := s.encodeChange(context.Background(), change, body)
	if err != nil {
		t.Fatal(err)
	}
	var ref objectRef
	_ = json.Unmarshal(raw, &ref)
	var mu sync.Mutex
	data := map[string][]byte{}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if !strings.HasPrefix(r.Header.Get("Authorization"), "AWS4-HMAC-SHA256 Credential=test-key/") {
			t.Error("request was not SigV4 signed")
			w.WriteHeader(403)
			return
		}
		if strings.Contains(r.URL.RawQuery, "secret") {
			t.Error("secret in query")
		}
		key := r.URL.Path
		switch r.Method {
		case "PUT":
			if r.Header.Get("If-None-Match") != "*" {
				t.Error("immutable write missing condition")
			}
			if _, ok := data[key]; ok {
				w.WriteHeader(412)
				_, _ = w.Write([]byte("<Error><Code>PreconditionFailed</Code></Error>"))
				return
			}
			data[key], _ = io.ReadAll(r.Body)
			w.WriteHeader(200)
		case "GET":
			b, ok := data[key]
			if !ok {
				w.WriteHeader(404)
				return
			}
			_, _ = w.Write(b)
		case "DELETE":
			delete(data, key)
			w.WriteHeader(204)
		}
	}))
	defer server.Close()
	objects, err := NewS3Objects(server.URL, "bucket", "test-key", "synthetic-secret", "auto", false)
	if err != nil {
		t.Fatal(err)
	}
	// Trust only this in-process TLS fixture certificate, never disable production TLS.
	objects.client = s3.NewFromConfig(aws.Config{Region: "auto", Credentials: objects.client.Options().Credentials, HTTPClient: server.Client(), RequestChecksumCalculation: aws.RequestChecksumCalculationWhenRequired}, func(o *s3.Options) { o.BaseEndpoint = aws.String(server.URL); o.UsePathStyle = true })
	if err = objects.Put(context.Background(), ref.key(), body); err != nil {
		t.Fatal(err)
	}
	if err = objects.Put(context.Background(), ref.key(), body); err != nil {
		t.Fatal("idempotent existing object", err)
	}
	mu.Lock()
	data["/bucket/"+ref.key()] = []byte("tampered")
	mu.Unlock()
	if _, err = objects.Get(context.Background(), ref.key()); err == nil {
		t.Fatal("corrupt object accepted")
	}
	if err = objects.Put(context.Background(), ref.key(), body); err == nil {
		t.Fatal("corrupt existing object treated as successful retry")
	}
	if err = objects.Probe(context.Background()); err != nil {
		t.Fatal("write/read/delete probe", err)
	}
}

type testObjects struct {
	mu   sync.Mutex
	data map[string][]byte
	fail bool
}

func (o *testObjects) Put(_ context.Context, key string, body []byte) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.fail {
		return errObject
	}
	o.data[key] = append([]byte(nil), body...)
	return nil
}
func (o *testObjects) Get(_ context.Context, key string) ([]byte, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.fail {
		return nil, errObject
	}
	b, ok := o.data[key]
	if !ok {
		return nil, errObject
	}
	return append([]byte(nil), b...), nil
}
func (o *testObjects) Probe(context.Context) error { return nil }
func TestPostgresObjectFailureRollback(t *testing.T) {
	s := database(t)
	_, actor := provision(t, s)
	s.Objects = &testObjects{fail: true}
	if _, err := s.Push(context.Background(), actor, operation(t)); err == nil {
		t.Fatal("failed external write committed")
	}
	var revision int64
	if err := s.DB.QueryRow(`SELECT revision FROM zapime_accounts WHERE id=$1`, actor.AccountID).Scan(&revision); err != nil || revision != 0 {
		t.Fatal("revision advanced on failed object write", err)
	}
}
func liveObjects(t *testing.T, bucket string) *S3Objects {
	t.Helper()
	endpoint := os.Getenv("ZAPIME_TEST_S3_ENDPOINT")
	if endpoint == "" {
		if os.Getenv("ZAPIME_REQUIRE_S3") == "1" {
			t.Fatal("S3 test service required")
		}
		t.Skip("set ZAPIME_TEST_S3_ENDPOINT for real S3-compatible storage acceptance")
	}
	objects, err := NewS3Objects(endpoint, bucket, "zapime-ci-key", "zapime-ci-synthetic-secret", "us-east-1", true)
	if err != nil {
		t.Fatal(err)
	}
	_, err = objects.client.CreateBucket(context.Background(), &s3.CreateBucketInput{Bucket: aws.String(bucket)})
	var api smithy.APIError
	if err != nil && (!errors.As(err, &api) || (api.ErrorCode() != "BucketAlreadyOwnedByYou" && api.ErrorCode() != "BucketAlreadyExists")) {
		t.Fatal(err)
	}
	return objects
}
func TestPostgresRealObjectStorageAndPortableArchive(t *testing.T) {
	// Real PostgreSQL and a real S3-compatible server, not an in-memory repository.
	objects := liveObjects(t, "zapime-ci")
	_ = liveObjects(t, "zapime-ci-install")
	_ = liveObjects(t, "zapime-ci-install-restored")
	restored := liveObjects(t, "zapime-ci-restore")
	s := database(t)
	s.Objects = objects
	ctx := context.Background()
	if err := objects.Probe(ctx); err != nil {
		t.Fatal(err)
	}
	_, actor := provision(t, s)
	op := operation(t)
	first, err := s.Push(ctx, actor, op)
	if err != nil {
		t.Fatal(err)
	}
	retry, err := s.Push(ctx, actor, op)
	if err != nil || retry.Revision != first.Revision {
		t.Fatal("external idempotency", err)
	}
	var raw []byte
	if err = s.DB.QueryRow(`SELECT change FROM zapime_operations WHERE account_id=$1`, actor.AccountID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, []byte(op.Envelope.Ciphertext)) || !bytes.Contains(raw, []byte("storage_version")) {
		t.Fatal("ciphertext duplicated in metadata database")
	}
	page, err := s.Pull(ctx, actor, 0, 100)
	if err != nil || len(page.Changes) != 1 || page.Changes[0].Operation != op {
		t.Fatal("external pull", err)
	}
	op.BaseRevision = first.Revision
	op.OperationID = id(t)
	op.Deleted = true
	deleted, err := s.Push(ctx, actor, op)
	if err != nil {
		t.Fatal(err)
	}
	stale := op
	stale.OperationID = id(t)
	stale.BaseRevision = 0
	_, err = s.Push(ctx, actor, stale)
	var conflict *Conflict
	if !errors.As(err, &conflict) || conflict.Current == nil || !conflict.Current.Operation.Deleted {
		t.Fatal("external tombstone conflict", err)
	}
	// A private, standard tar exports all immutable referenced ciphertexts.
	var archive bytes.Buffer
	if err = s.ExportObjects(ctx, &archive); err != nil {
		t.Fatal(err)
	}
	if err = ImportObjects(ctx, restored, bytes.NewReader(archive.Bytes())); err != nil {
		t.Fatal(err)
	}
	if err = ImportObjects(ctx, restored, bytes.NewReader(archive.Bytes())); err != nil {
		t.Fatal("repeat import", err)
	}
	migrated := &Store{DB: s.DB, Objects: restored}
	page, err = migrated.Pull(ctx, actor, 0, 100)
	if err != nil || len(page.Changes) != 2 || page.Changes[1].Revision != deleted.Revision {
		t.Fatal("alternate empty object store recovery", err)
	}
	// Missing/corrupt external data cannot be acknowledged as an empty/partial page.
	var ref objectRef
	_ = json.Unmarshal(raw, &ref)
	original, err := objects.Get(ctx, ref.key())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = objects.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(objects.bucket), Key: aws.String(ref.key())}); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Pull(ctx, actor, 0, 100); err == nil {
		t.Fatal("missing object returned successful page")
	}
	if err = objects.Put(ctx, ref.key(), original); err != nil {
		t.Fatal(err)
	}
	if err = (&Store{DB: s.DB}).CheckConfiguration(ctx); err == nil {
		t.Fatal("external DB allowed silent PostgreSQL-only startup")
	}
}
func TestObjectArchiveRejectsTraversalAndTampering(t *testing.T) {
	for _, name := range []string{"../../escape", "zapime/v1/11111111-1111-4111-8111-111111111111/" + strings.Repeat("0", 64) + ".json"} {
		var b bytes.Buffer
		w := tar.NewWriter(&b)
		_ = w.WriteHeader(&tar.Header{Name: name, Size: 4, Mode: 0600})
		_, _ = w.Write([]byte("junk"))
		_ = w.Close()
		if err := ImportObjects(context.Background(), &testObjects{data: map[string][]byte{}}, &b); err == nil {
			t.Fatal("unsafe archive accepted")
		}
	}
}
