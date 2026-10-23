// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sync"
	"testing"
	"time"
)

type countedObjects struct {
	testObjects
	gets, puts, probes int
	started, release   chan struct{}
	once               sync.Once
}

func (o *countedObjects) Get(ctx context.Context, key string) ([]byte, error) {
	o.mu.Lock()
	o.gets++
	o.mu.Unlock()
	if o.started != nil {
		o.once.Do(func() { close(o.started) })
		select {
		case <-o.release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return o.testObjects.Get(ctx, key)
}
func (o *countedObjects) Put(ctx context.Context, key string, body []byte) error {
	o.mu.Lock()
	o.puts++
	o.mu.Unlock()
	return o.testObjects.Put(ctx, key, body)
}
func (o *countedObjects) Probe(context.Context) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.probes++
	return nil
}
func cacheFixture(t *testing.T) (*countedObjects, string, []byte) {
	t.Helper()
	body := []byte(`{"synthetic":"ciphertext fixture"}`)
	sum := sha256.Sum256(body)
	key := "zapime/v1/" + id(t) + "/" + hex.EncodeToString(sum[:]) + ".json"
	return &countedObjects{testObjects: testObjects{data: map[string][]byte{key: body}}}, key, body
}

func TestObjectCacheReadWriteIsolationAndDurability(t *testing.T) {
	backend, key, body := cacheFixture(t)
	cache := NewCachedObjects(backend, 4096, time.Minute)
	ctx := context.Background()
	first, err := cache.Get(ctx, key)
	if err != nil {
		t.Fatal(err)
	}
	first[0] = '!'
	second, err := cache.Get(ctx, key)
	if err != nil || string(second) != string(body) || backend.gets != 1 {
		t.Fatal("cache miss/caller mutation leaked", err)
	}
	if err = cache.Put(ctx, key, body); err != nil {
		t.Fatal(err)
	}
	backend.fail = true
	if err = cache.Put(ctx, key, body); err == nil || backend.puts != 2 {
		t.Fatal("cached bytes replaced durable write acknowledgement")
	}
	// An account-scoped key can never hit another account's cached object.
	other := "zapime/v1/" + id(t) + key[len("zapime/v1/")+36:]
	if _, err = cache.Get(ctx, other); err == nil {
		t.Fatal("cross-account cache hit")
	}
	if err = cache.Probe(ctx); err != nil || backend.probes != 1 {
		t.Fatal("probe skipped backend", err)
	}
}

func TestObjectCacheExpiryEvictionAndFailedReads(t *testing.T) {
	backend, key, body := cacheFixture(t)
	cache := NewCachedObjects(backend, len(key)+len(body)+128, time.Minute).(*cachedObjects)
	now := time.Now()
	cache.now = func() time.Time { return now }
	ctx := context.Background()
	if _, err := cache.Get(ctx, key); err != nil {
		t.Fatal(err)
	}
	now = now.Add(time.Minute)
	if _, err := cache.Get(ctx, key); err != nil || backend.gets != 2 {
		t.Fatal("expired entry did not refresh", err)
	}
	other := "zapime/v1/" + id(t) + key[len("zapime/v1/")+36:]
	backend.data[other] = body
	if _, err := cache.Get(ctx, other); err != nil {
		t.Fatal(err)
	}
	if _, err := cache.Get(ctx, key); err != nil || backend.gets != 4 || cache.used > cache.limit || len(cache.entries) != 1 {
		t.Fatal("bounded LRU eviction failed", err)
	}
	now = now.Add(time.Minute)
	backend.data[key] = []byte("corrupt")
	if _, err := cache.Get(ctx, key); err == nil {
		t.Fatal("corrupt durable bytes accepted")
	}
	backend.data[key] = body
	if _, err := cache.Get(ctx, key); err != nil || backend.gets != 6 {
		t.Fatal("failure was cached instead of retried", err)
	}
	if NewCachedObjects(nil, 1, time.Minute) != nil || NewCachedObjects(backend, 0, time.Minute) != backend {
		t.Fatal("disabled cache changed backend")
	}
}

func TestObjectCacheConcurrentReadersAndCancellation(t *testing.T) {
	backend, key, body := cacheFixture(t)
	backend.started, backend.release = make(chan struct{}), make(chan struct{})
	cache := NewCachedObjects(backend, 4096, time.Minute)
	ctx, cancel := context.WithCancel(context.Background())
	first := make(chan error, 1)
	go func() { _, err := cache.Get(ctx, key); first <- err }()
	<-backend.started
	cancel()
	if err := <-first; !errors.Is(err, context.Canceled) {
		t.Fatal("cancelled caller did not return", err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			got, err := cache.Get(context.Background(), key)
			if err != nil || string(got) != string(body) {
				t.Error("shared fetch cancelled by disconnected device", err)
			}
		}()
	}
	close(backend.release)
	wg.Wait()
	backend.mu.Lock()
	defer backend.mu.Unlock()
	if backend.gets != 1 {
		t.Fatal("concurrent devices duplicated cloud reads", backend.gets)
	}
}

func TestObjectCacheWarmAfterSuccessfulWrite(t *testing.T) {
	backend, key, body := cacheFixture(t)
	cache := NewCachedObjects(backend, 4096, time.Minute)
	ctx := context.Background()
	if err := cache.Put(ctx, key, body); err != nil {
		t.Fatal(err)
	}
	if _, err := cache.Get(ctx, key); err != nil || backend.gets != 0 {
		t.Fatal("first device download after upload reread cloud", err)
	}
}

func TestPostgresObjectCacheAuthorizationAndEmptyCursor(t *testing.T) {
	s := database(t)
	_, actor := provision(t, s)
	// This test intentionally uses synthetic in-memory objects. Do not leave
	// their references in the shared integration DB for real S3 archive tests.
	t.Cleanup(func() {
		for _, query := range []string{
			`DELETE FROM zapime_operations WHERE account_id=$1`,
			`DELETE FROM zapime_records WHERE account_id=$1`,
			`DELETE FROM zapime_devices WHERE account_id=$1`,
			`DELETE FROM zapime_accounts WHERE id=$1`,
		} {
			if _, err := s.DB.Exec(query, actor.AccountID); err != nil {
				t.Error("synthetic cache fixture cleanup", err)
			}
		}
	})
	backend, _, _ := cacheFixture(t)
	s.Objects = NewCachedObjects(backend, 4096, time.Minute)
	ctx := context.Background()
	change, err := s.Push(ctx, actor, operation(t))
	if err != nil {
		t.Fatal(err)
	}
	page, err := s.Pull(ctx, actor, 0, 100)
	if err != nil || len(page.Changes) != 1 || backend.gets != 0 {
		t.Fatal("pull did not use successfully stored ciphertext cache", err)
	}
	page, err = s.Pull(ctx, actor, change.Revision, 100)
	if err != nil || len(page.Changes) != 0 || backend.gets != 0 {
		t.Fatal("unchanged cursor caused object read", err)
	}
	if err = s.Revoke(ctx, actor, actor.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Pull(ctx, actor, 0, 100); err == nil || backend.gets != 0 {
		t.Fatal("cached object bypassed device revocation")
	}
}
