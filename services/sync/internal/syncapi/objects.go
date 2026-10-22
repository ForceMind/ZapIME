// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"archive/tar"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"
)

const maxObjectBytes = 100000

var errObject = errors.New("object_storage_unavailable")
var objectPattern = regexp.MustCompile(`^zapime/v1/([a-f0-9-]{36})/([a-f0-9]{64})\.json$`)

// Immutable, content-addressed objects. No deletion API for user history/tombstones.
type ObjectStore interface {
	Put(context.Context, string, []byte) error
	Get(context.Context, string) ([]byte, error)
	Probe(context.Context) error
}
type S3Objects struct {
	client *s3.Client
	bucket string
}

func ObjectsFromEnv() (ObjectStore, error) {
	mode := os.Getenv("ZAPIME_OBJECT_BACKEND")
	if mode == "" || mode == "postgres" {
		for _, name := range []string{"ZAPIME_S3_ENDPOINT", "ZAPIME_S3_BUCKET", "ZAPIME_S3_ACCESS_KEY_ID", "ZAPIME_S3_SECRET_ACCESS_KEY"} {
			if os.Getenv(name) != "" {
				return nil, errors.New("object_backend_required_for_s3_configuration")
			}
		}
		return nil, nil
	}
	if mode != "s3" {
		return nil, errors.New("invalid_object_backend")
	}
	return NewS3Objects(os.Getenv("ZAPIME_S3_ENDPOINT"), os.Getenv("ZAPIME_S3_BUCKET"), os.Getenv("ZAPIME_S3_ACCESS_KEY_ID"), os.Getenv("ZAPIME_S3_SECRET_ACCESS_KEY"), os.Getenv("ZAPIME_S3_REGION"), os.Getenv("ZAPIME_S3_ALLOW_HTTP") == "1")
}
func NewS3Objects(endpoint, bucket, key, secret, region string, allowHTTP bool) (*S3Objects, error) {
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "https" && !(allowHTTP && u.Scheme == "http")) || bucket == "" || strings.ContainsAny(bucket, "/\\ \n\r") || key == "" || secret == "" {
		return nil, errors.New("invalid_object_configuration")
	}
	if region == "" {
		region = "auto"
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.MaxIdleConnsPerHost = 8
	client := &http.Client{Transport: transport, Timeout: 4 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	config := aws.Config{Region: region, Credentials: credentials.NewStaticCredentialsProvider(key, secret, ""), HTTPClient: client, RetryMaxAttempts: 2, RequestChecksumCalculation: aws.RequestChecksumCalculationWhenRequired, ResponseChecksumValidation: aws.ResponseChecksumValidationWhenRequired}
	return &S3Objects{client: s3.NewFromConfig(config, func(o *s3.Options) {
		o.BaseEndpoint = aws.String(strings.TrimSuffix(endpoint, "/"))
		o.UsePathStyle = true
	}), bucket: bucket}, nil
}
func validObject(key string, body []byte) bool {
	parts := objectPattern.FindStringSubmatch(key)
	if len(parts) != 3 || !validID(parts[1]) || len(body) > maxObjectBytes {
		return false
	}
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:]) == parts[2]
}
func (s *S3Objects) Put(ctx context.Context, key string, body []byte) error {
	if !validObject(key, body) {
		return errObject
	}
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key), Body: bytes.NewReader(body), ContentLength: aws.Int64(int64(len(body))), ContentType: aws.String("application/json"), StorageClass: types.StorageClassStandard, IfNoneMatch: aws.String("*")})
	if err == nil {
		return nil
	}
	var api smithy.APIError
	if errors.As(err, &api) && api.ErrorCode() == "PreconditionFailed" {
		existing, readErr := s.Get(ctx, key)
		if readErr == nil && bytes.Equal(existing, body) {
			return nil
		}
	}
	return errObject
}
func (s *S3Objects) Get(ctx context.Context, key string) ([]byte, error) {
	if !objectPattern.MatchString(key) {
		return nil, errObject
	}
	result, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key)})
	if err != nil {
		return nil, errObject
	}
	defer result.Body.Close()
	body, err := io.ReadAll(io.LimitReader(result.Body, maxObjectBytes+1))
	if err != nil || !validObject(key, body) {
		return nil, errObject
	}
	return body, nil
}
func (s *S3Objects) Probe(ctx context.Context) error {
	id, err := UUID()
	if err != nil {
		return errObject
	}
	key := "zapime/check/" + id
	body := []byte("ZapIME synthetic storage probe\n")
	_, err = s.client.PutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key), Body: bytes.NewReader(body), ContentType: aws.String("text/plain"), StorageClass: types.StorageClassStandard, IfNoneMatch: aws.String("*")})
	if err != nil {
		return errObject
	}
	// Probe is the only deletable object; user history is never deleted here.
	result, getErr := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key)})
	good := false
	if getErr == nil {
		data, readErr := io.ReadAll(io.LimitReader(result.Body, 1024))
		result.Body.Close()
		good = readErr == nil && bytes.Equal(data, body)
	}
	_, deleteErr := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key)})
	if !good || deleteErr != nil {
		return errObject
	}
	return nil
}

type objectRef struct {
	Version   int    `json:"storage_version"`
	AccountID string `json:"account_id"`
	SHA256    string `json:"sha256"`
}

func (r objectRef) key() string { return "zapime/v1/" + r.AccountID + "/" + r.SHA256 + ".json" }
func (s *Store) encodeChange(ctx context.Context, change Change, body []byte) ([]byte, error) {
	if s.Objects == nil {
		return body, nil
	}
	sum := sha256.Sum256(body)
	ref := objectRef{2, change.AccountID, hex.EncodeToString(sum[:])}
	if err := s.Objects.Put(ctx, ref.key(), body); err != nil {
		return nil, errObject
	}
	return json.Marshal(ref)
}
func (s *Store) decodeChange(ctx context.Context, account string, body []byte) (Change, error) {
	var ref objectRef
	if err := json.Unmarshal(body, &ref); err != nil {
		return Change{}, errObject
	}
	if ref.Version != 0 {
		if ref.Version != 2 || ref.AccountID != account || s.Objects == nil || !objectPattern.MatchString(ref.key()) {
			return Change{}, errObject
		}
		var err error
		body, err = s.Objects.Get(ctx, ref.key())
		if err != nil || !validObject(ref.key(), body) {
			return Change{}, errObject
		}
	}
	var change Change
	if err := json.Unmarshal(body, &change); err != nil || change.AccountID != account || change.Revision < 1 || !validID(change.DeviceID) || change.Operation.Validate() != nil {
		return Change{}, errObject
	}
	return change, nil
}

// pg_dump must finish BEFORE this append-only object export. Objects have no GC;
// therefore this later superset contains every reference in that DB snapshot.
func (s *Store) ExportObjects(ctx context.Context, out io.Writer) error {
	rows, err := s.DB.QueryContext(ctx, `SELECT DISTINCT change FROM zapime_operations WHERE change->>'storage_version'='2'`)
	if err != nil {
		return err
	}
	defer rows.Close()
	tw := tar.NewWriter(out)
	for rows.Next() {
		var raw []byte
		if err = rows.Scan(&raw); err != nil {
			return err
		}
		var ref objectRef
		if err = json.Unmarshal(raw, &ref); err != nil || ref.Version != 2 || s.Objects == nil {
			return errObject
		}
		body, err := s.Objects.Get(ctx, ref.key())
		if err != nil || !validObject(ref.key(), body) {
			return errObject
		}
		if err = tw.WriteHeader(&tar.Header{Name: ref.key(), Mode: 0600, Size: int64(len(body)), Typeflag: tar.TypeReg}); err != nil {
			return err
		}
		if _, err = tw.Write(body); err != nil {
			return err
		}
	}
	if err = rows.Err(); err != nil {
		return err
	}
	return tw.Close()
}
func ImportObjects(ctx context.Context, objects ObjectStore, in io.Reader) error {
	if objects == nil {
		return errObject
	}
	tr := tar.NewReader(in)
	for {
		header, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		if header.Typeflag != tar.TypeReg || header.Size < 1 || header.Size > maxObjectBytes || !objectPattern.MatchString(header.Name) {
			return errObject
		}
		body, err := io.ReadAll(io.LimitReader(tr, maxObjectBytes+1))
		if err != nil || !validObject(header.Name, body) {
			return errObject
		}
		var change Change
		if err = json.Unmarshal(body, &change); err != nil || change.Operation.Validate() != nil || !strings.HasPrefix(header.Name, "zapime/v1/"+change.AccountID+"/") {
			return errObject
		}
		if err = objects.Put(ctx, header.Name, body); err != nil {
			return errObject
		}
	}
}

// Keep pagination order, bound object I/O to eight requests, and return no page
// on any missing/corrupt object; the client must not advance its cursor.
func (s *Store) decodeChanges(ctx context.Context, account string, bodies [][]byte) ([]Change, error) {
	changes := make([]Change, len(bodies))
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	jobs := make(chan int)
	failures := make(chan error, 1)
	var wg sync.WaitGroup
	for w := 0; w < 8; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				change, err := s.decodeChange(ctx, account, bodies[i])
				if err != nil {
					select {
					case failures <- err:
					default:
					}
					cancel()
					return
				}
				changes[i] = change
			}
		}()
	}
dispatch:
	for i := range bodies {
		select {
		case jobs <- i:
		case <-ctx.Done():
			break dispatch
		}
	}
	close(jobs)
	wg.Wait()
	select {
	case err := <-failures:
		return nil, err
	default:
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return changes, nil
}
func (s *Store) CheckConfiguration(ctx context.Context) error {
	if s.Objects != nil {
		return nil
	}
	var external bool
	err := s.DB.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM zapime_operations WHERE change->>'storage_version'='2')`).Scan(&external)
	if err != nil {
		return err
	}
	if external {
		return errors.New("object_storage_required_for_database")
	}
	return nil
}
