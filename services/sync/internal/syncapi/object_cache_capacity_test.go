// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// All objects and account IDs are synthetic. Explicit start/release barriers
// keep cancellation ordering deterministic without cloud traffic or sleeps.
type gatedCacheObjects struct {
	mu           sync.Mutex
	data         map[string][]byte
	active, peak int
	calls        int
	started      chan string
	finished     chan string
	release      chan struct{}
	gates        map[string]chan struct{}
	once         sync.Once
}

func newGatedCacheObjects(t *testing.T, count int) (*gatedCacheObjects, []string) {
	t.Helper()
	o := &gatedCacheObjects{data: make(map[string][]byte), gates: make(map[string]chan struct{}), started: make(chan string, count),
		finished: make(chan string, count), release: make(chan struct{})}
	keys := make([]string, count)
	account := id(t)
	for i := range keys {
		body := []byte(fmt.Sprintf(`{"synthetic_ciphertext":%d}`, i))
		sum := sha256.Sum256(body)
		keys[i] = "zapime/v1/" + account + "/" + hex.EncodeToString(sum[:]) + ".json"
		o.data[keys[i]] = body
		o.gates[keys[i]] = make(chan struct{})
	}
	t.Cleanup(func() { o.once.Do(func() { close(o.release) }) })
	return o, keys
}

func (o *gatedCacheObjects) Get(ctx context.Context, key string) ([]byte, error) {
	o.mu.Lock()
	o.calls++
	o.active++
	if o.active > o.peak {
		o.peak = o.active
	}
	o.mu.Unlock()
	defer func() {
		o.mu.Lock()
		o.active--
		o.mu.Unlock()
		o.finished <- key
	}()
	o.started <- key
	select {
	case <-o.release:
		return append([]byte(nil), o.data[key]...), nil
	case <-o.gates[key]:
		return append([]byte(nil), o.data[key]...), nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Done is evaluated only after Get has joined the existing fill's wait select.
// This handshake proves a second waiter joined before releasing the backend.
type cacheWaitContext struct {
	context.Context
	entered chan struct{}
	once    sync.Once
}

func (c *cacheWaitContext) Done() <-chan struct{} {
	c.once.Do(func() { close(c.entered) })
	return c.Context.Done()
}

func TestObjectCacheFullCapacityStillSharesAndReleasesOneSlot(t *testing.T) {
	backend, keys := newGatedCacheObjects(t, 3)
	cache := NewCachedObjects(backend, 64*1024, time.Minute).(*cachedObjects)
	cache.fillLimit = 2
	for _, key := range keys[:2] {
		ctx, cancel := context.WithCancel(context.Background())
		returned := make(chan error, 1)
		go func() { _, err := cache.Get(ctx, key); returned <- err }()
		awaitCacheRead(t, backend.started)
		cancel()
		if err := awaitCacheError(t, returned); !errors.Is(err, context.Canceled) {
			t.Fatal("cancelled first waiter did not leave", err)
		}
	}
	joined := &cacheWaitContext{Context: context.Background(), entered: make(chan struct{})}
	returned := make(chan error, 1)
	go func() {
		body, err := cache.Get(joined, keys[0])
		if err == nil && string(body) != string(backend.data[keys[0]]) {
			err = errors.New("wrong shared synthetic ciphertext")
		}
		returned <- err
	}()
	select {
	case <-joined.entered:
	case err := <-returned:
		t.Fatal("same-key waiter rejected at full capacity", err)
	case <-time.After(3 * time.Second):
		t.Fatal("same-key waiter could not join full cache")
	}
	if _, err := cache.Get(context.Background(), keys[2]); !errors.Is(err, errObjectCacheBusy) {
		t.Fatal("different key was admitted while every fill slot was held", err)
	}
	close(backend.gates[keys[0]])
	if err := awaitCacheError(t, returned); err != nil {
		t.Fatal("first caller cancellation cancelled a joined device", err)
	}
	if _, err := cache.Get(context.Background(), keys[0]); err != nil {
		t.Fatal("warm hit was subject to fill admission", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _, err := cache.Get(ctx, keys[2]); returned <- err }()
	if key := awaitCacheRead(t, backend.started); key != keys[2] {
		t.Fatal("released slot started a duplicate rather than the admitted key")
	}
	cancel()
	if err := awaitCacheError(t, returned); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	backend.mu.Lock()
	defer backend.mu.Unlock()
	if backend.peak != 2 || backend.calls != 3 {
		t.Fatal("shared/warm reads duplicated fills or exceeded capacity", backend.peak, backend.calls)
	}
}

type ignoringCacheObjects struct {
	*gatedCacheObjects
	cancelObserved chan struct{}
}

func (o *ignoringCacheObjects) Get(ctx context.Context, key string) ([]byte, error) {
	select {
	case <-o.release:
		return o.gatedCacheObjects.Get(ctx, key)
	default:
	}
	// Deliberately violate cooperative cancellation to prove that the wrapper
	// does not release capacity on a timer or accept a late success.
	o.mu.Lock()
	o.calls++
	o.active++
	if o.active > o.peak {
		o.peak = o.active
	}
	o.mu.Unlock()
	o.started <- key
	<-ctx.Done()
	close(o.cancelObserved)
	<-o.release
	o.mu.Lock()
	o.active--
	o.mu.Unlock()
	o.finished <- key
	return append([]byte(nil), o.data[key]...), nil
}

// This is an actual PostgreSQL authorization/HTTP test with synthetic objects,
// never a fake transaction or an R2 request. It must run in the service DB CI.
func TestPostgresObjectCacheHTTPAdmissionOutlivesCancelledHandlers(t *testing.T) {
	s := database(t)
	credential, actor := provision(t, s)
	t.Cleanup(func() {
		for _, query := range []string{
			`DELETE FROM zapime_operations WHERE account_id=$1`,
			`DELETE FROM zapime_records WHERE account_id=$1`,
			`DELETE FROM zapime_devices WHERE account_id=$1`,
			`DELETE FROM zapime_accounts WHERE id=$1`,
		} {
			if _, err := s.DB.Exec(query, actor.AccountID); err != nil {
				t.Error("synthetic HTTP cache fixture cleanup", err)
			}
		}
	})
	objects := &testObjects{data: make(map[string][]byte)}
	s.Objects = objects
	for range 3 {
		if _, err := s.Push(context.Background(), actor, operation(t)); err != nil {
			t.Fatal("synthetic cache admission seed", err)
		}
	}
	backend := &gatedCacheObjects{data: objects.data, gates: make(map[string]chan struct{}),
		started: make(chan string, 3), finished: make(chan string, 3), release: make(chan struct{})}
	for key := range backend.data {
		backend.gates[key] = make(chan struct{})
	}
	t.Cleanup(func() { backend.once.Do(func() { close(backend.release) }) })
	cache := NewCachedObjects(backend, 64*1024, time.Minute).(*cachedObjects)
	cache.fillLimit = 2
	s.Objects = cache
	api := NewAPI(s)
	request := func(ctx context.Context, after int) (*httptest.ResponseRecorder, <-chan struct{}) {
		r := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/v1/changes?after=%d&limit=1", after), nil).WithContext(ctx)
		r.Header.Set("Authorization", "Bearer "+credential.Token)
		w := httptest.NewRecorder()
		done := make(chan struct{})
		go func() { api.ServeHTTP(w, r); close(done) }()
		return w, done
	}
	wait := func(done <-chan struct{}) {
		t.Helper()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("synthetic HTTP cache request did not complete")
		}
	}
	firstKey := ""
	for after := range 2 {
		ctx, cancel := context.WithCancel(context.Background())
		_, done := request(ctx, after)
		key := awaitCacheRead(t, backend.started)
		if after == 0 {
			firstKey = key
		}
		cancel()
		wait(done)
		if len(api.slots) != 0 {
			t.Fatal("cancelled HTTP handler kept its own slot")
		}
	}
	w, done := request(context.Background(), 2)
	wait(done)
	var failure map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &failure); err != nil || w.Code != http.StatusServiceUnavailable || len(failure) != 1 || failure["error"] != "storage_unavailable" {
		t.Fatal("cache overload was not a whole-page storage failure", w.Code, err)
	}
	backend.mu.Lock()
	active, peak, calls := backend.active, backend.peak, backend.calls
	backend.mu.Unlock()
	if active != 2 || peak != 2 || calls != 2 || len(api.slots) != 0 {
		t.Fatal("released HTTP slots admitted excess detached fills", active, peak, calls)
	}
	cache.mu.Lock()
	fill := cache.flights[firstKey]
	cache.mu.Unlock()
	if fill == nil {
		t.Fatal("synthetic HTTP cache fill unexpectedly ended before its release barrier")
	}
	close(backend.gates[firstKey])
	wait(fill.done)
	w, done = request(context.Background(), 2)
	key := awaitCacheRead(t, backend.started)
	close(backend.gates[key])
	wait(done)
	var page Page
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil || w.Code != http.StatusOK || len(page.Changes) != 1 || page.Changes[0].Revision != 3 || page.NextCursor != 3 || page.Head != 3 {
		t.Fatal("same cursor could not retry after real backend exit", w.Code, err)
	}
}

func TestObjectCacheDeadlineDoesNotReleaseRunningBackendOrWarmLateBytes(t *testing.T) {
	gated, keys := newGatedCacheObjects(t, 2)
	backend := &ignoringCacheObjects{gatedCacheObjects: gated, cancelObserved: make(chan struct{})}
	cache := NewCachedObjects(backend, 64*1024, time.Minute).(*cachedObjects)
	cache.fillLimit, cache.fillTimeout = 1, 20*time.Millisecond
	returned := make(chan error, 1)
	go func() { _, err := cache.Get(context.Background(), keys[0]); returned <- err }()
	awaitCacheRead(t, backend.started)
	cache.mu.Lock()
	fill := cache.flights[keys[0]]
	cache.mu.Unlock()
	select {
	case <-backend.cancelObserved:
	case <-time.After(3 * time.Second):
		t.Fatal("backend never observed its independent deadline")
	}
	if err := awaitCacheError(t, returned); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("backend ignoring cancellation blocked the caller beyond the fill deadline", err)
	}
	if _, err := cache.Get(context.Background(), keys[1]); !errors.Is(err, errObjectCacheBusy) {
		t.Fatal("deadline released a physically running backend's capacity", err)
	}
	backend.once.Do(func() { close(backend.release) })
	select {
	case <-fill.done:
	case <-time.After(3 * time.Second):
		t.Fatal("actual backend completion did not release fill")
	}
	if _, hit := cache.lookup(keys[0]); hit {
		t.Fatal("late backend success was cached after the deadline")
	}
	if _, err := cache.Get(context.Background(), keys[1]); err != nil {
		t.Fatal("actual backend exit leaked admission capacity", err)
	}
	backend.mu.Lock()
	defer backend.mu.Unlock()
	if backend.peak != 1 || backend.active != 0 {
		t.Fatal("timeout escaped capacity or left a synthetic backend running", backend.peak, backend.active)
	}
}

func TestObjectCacheFailureAndCorruptReadReleaseAdmissionForRetry(t *testing.T) {
	backend, key, body := cacheFixture(t)
	cache := NewCachedObjects(backend, 4096, time.Minute).(*cachedObjects)
	cache.fillLimit = 1
	backend.fail = true
	if _, err := cache.Get(context.Background(), key); err == nil {
		t.Fatal("failed backend was accepted")
	}
	backend.fail = false
	backend.data[key] = []byte("corrupt synthetic bytes")
	if _, err := cache.Get(context.Background(), key); !errors.Is(err, errObject) {
		t.Fatal("wrong SHA did not fail", err)
	}
	backend.data[key] = body
	if got, err := cache.Get(context.Background(), key); err != nil || string(got) != string(body) {
		t.Fatal("failed/corrupt fills leaked a slot or cached an error", err)
	}
	cache.mu.Lock()
	defer cache.mu.Unlock()
	if len(cache.flights) != 0 || backend.gets != 3 {
		t.Fatal("failed fill state leaked", len(cache.flights), backend.gets)
	}
}

func TestObjectCachePrecancelledCallerDoesNotStartFill(t *testing.T) {
	backend, key, _ := cacheFixture(t)
	cache := NewCachedObjects(backend, 4096, time.Minute).(*cachedObjects)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := cache.Get(ctx, key); !errors.Is(err, context.Canceled) {
		t.Fatal("pre-cancelled caller did not fail", err)
	}
	if backend.gets != 0 || len(cache.flights) != 0 {
		t.Fatal("pre-cancelled request consumed detached resources")
	}
}
func (o *gatedCacheObjects) Put(context.Context, string, []byte) error { return errObject }
func (o *gatedCacheObjects) Probe(context.Context) error               { return nil }

func awaitCacheRead(t *testing.T, events <-chan string) string {
	t.Helper()
	select {
	case key := <-events:
		return key
	case <-time.After(3 * time.Second):
		t.Fatal("synthetic cache backend barrier timed out")
		return ""
	}
}

func awaitCacheError(t *testing.T, events <-chan error) error {
	t.Helper()
	select {
	case err := <-events:
		return err
	case <-time.After(3 * time.Second):
		t.Fatal("synthetic cache caller did not return")
		return nil
	}
}

func TestObjectCacheAbandonedFillsRemainGloballyBounded(t *testing.T) {
	const capacity = 32
	backend, keys := newGatedCacheObjects(t, capacity+3)
	cache := NewCachedObjects(backend, 64*1024, time.Minute)
	for _, key := range keys[:capacity] {
		ctx, cancel := context.WithCancel(context.Background())
		returned := make(chan error, 1)
		go func() { _, err := cache.Get(ctx, key); returned <- err }()
		if started := awaitCacheRead(t, backend.started); started != key {
			t.Fatal("different synthetic cache key started")
		}
		cancel()
		select {
		case err := <-returned:
			if !errors.Is(err, context.Canceled) {
				t.Fatal("cancelled handler did not return while its fill remained active", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("cancelled cache caller remained blocked")
		}
	}
	for _, key := range keys[capacity:] {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		returned := make(chan error, 1)
		go func() { _, err := cache.Get(ctx, key); returned <- err }()
		select {
		case started := <-backend.started:
			t.Fatalf("abandoned fills exceeded capacity %d: extra backend read %s", capacity, started)
		case err := <-returned:
			if err == nil || err.Error() != "object_cache_busy" {
				t.Fatal("new-key overload did not fail explicitly", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("new-key overload queued instead of rejecting")
		}
	}
	backend.mu.Lock()
	if backend.active != capacity || backend.peak != capacity || backend.calls != capacity {
		t.Error("detached fills escaped process cache capacity", backend.active, backend.peak, backend.calls)
	}
	backend.mu.Unlock()
	backend.once.Do(func() { close(backend.release) })
	for range capacity {
		awaitCacheRead(t, backend.finished)
	}
}
