// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"container/list"
	"context"
	"errors"
	"sync"
	"time"
)

const objectCacheFillLimit = 32

var errObjectCacheBusy = errors.New("object_cache_busy")

// This disposable cache is only for the HTTP service. Backup/verification CLI
// commands use the underlying store directly, so they still check durable data.
// Authorization stays in Store before any cached object can be requested.
type cachedObjects struct {
	backend     ObjectStore
	mu          sync.Mutex
	entries     map[string]*list.Element
	lru         *list.List
	used        int
	limit       int
	ttl         time.Duration
	now         func() time.Time
	flights     map[string]*objectFill
	fillLimit   int
	fillTimeout time.Duration
}

// One shared completion per admitted key; no per-waiter queues or detached
// goroutine are allocated by later callers. Fields are published by closing done.
type objectFill struct {
	ctx  context.Context
	done chan struct{}
	body []byte
	err  error
}

type cachedObject struct {
	key     string
	body    []byte
	expires time.Time
}

func (e cachedObject) cost() int { return len(e.key) + len(e.body) + 128 }

// NewCachedObjects bounds ciphertext/key storage, copies mutable byte slices,
// and coalesces concurrent reads of the same account-scoped immutable key.
// It never acknowledges a write without a successful backend Put.
// Each cache instance admits at most 32 fills and has no waiting-key queue;
// the HTTP service constructs one instance shared across accounts and requests.
func NewCachedObjects(backend ObjectStore, maxBytes int, ttl time.Duration) ObjectStore {
	if backend == nil || maxBytes <= 0 || ttl <= 0 {
		return backend
	}
	return &cachedObjects{backend: backend, entries: make(map[string]*list.Element),
		lru: list.New(), limit: maxBytes, ttl: ttl, now: time.Now,
		flights: make(map[string]*objectFill), fillLimit: objectCacheFillLimit, fillTimeout: 5 * time.Second}
}

func (c *cachedObjects) remove(e *list.Element) {
	entry := e.Value.(cachedObject)
	c.used -= entry.cost()
	delete(c.entries, entry.key)
	c.lru.Remove(e)
}

func (c *cachedObjects) lookup(key string) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lookupLocked(key)
}

func (c *cachedObjects) lookupLocked(key string) ([]byte, bool) {
	if e := c.entries[key]; e != nil {
		entry := e.Value.(cachedObject)
		if !c.now().Before(entry.expires) {
			c.remove(e)
			return nil, false
		}
		c.lru.MoveToFront(e)
		return append([]byte(nil), entry.body...), true
	}
	return nil, false
}

func (c *cachedObjects) remember(key string, body []byte) {
	if !validObject(key, body) {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	entry := cachedObject{key, body, c.now().Add(c.ttl)}
	if entry.cost() > c.limit {
		return
	}
	if e := c.entries[key]; e != nil {
		c.remove(e)
	}
	for c.used+entry.cost() > c.limit {
		c.remove(c.lru.Back())
	}
	entry.body = append([]byte(nil), body...)
	c.entries[key] = c.lru.PushFront(entry)
	c.used += entry.cost()
}

func (c *cachedObjects) Put(ctx context.Context, key string, body []byte) error {
	if !validObject(key, body) {
		return errObject
	}
	if err := c.backend.Put(ctx, key, body); err != nil {
		return err
	}
	c.remember(key, body)
	return nil
}

func (c *cachedObjects) Get(ctx context.Context, key string) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	c.mu.Lock()
	if body, ok := c.lookupLocked(key); ok {
		c.mu.Unlock()
		return body, nil
	}
	fill := c.flights[key]
	if fill == nil {
		if len(c.flights) >= c.fillLimit {
			c.mu.Unlock()
			return nil, errObjectCacheBusy
		}
		// A disconnected caller cannot cancel another waiting device. When all
		// leave, a fill may finish warming within its own deadline and capacity.
		// Do not retain values from an HTTP request's context.
		fetchCtx, cancel := context.WithTimeout(context.Background(), c.fillTimeout)
		fill = &objectFill{ctx: fetchCtx, done: make(chan struct{})}
		c.flights[key] = fill
		go c.fetch(key, fill, cancel)
	}
	c.mu.Unlock()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-fill.ctx.Done():
		// A backend that ignores cancellation still owns its slot until it
		// really returns, but it cannot make a caller wait beyond the budget.
		select {
		case <-fill.done:
		default:
			return nil, fill.ctx.Err()
		}
	case <-fill.done:
	}
	if fill.err != nil {
		return nil, fill.err
	}
	return append([]byte(nil), fill.body...), nil
}

func (c *cachedObjects) fetch(key string, fill *objectFill, cancel context.CancelFunc) {
	defer cancel()
	body, err := c.backend.Get(fill.ctx, key)
	if err == nil && !validObject(key, body) {
		err = errObject
	}
	if deadlineErr := fill.ctx.Err(); deadlineErr != nil {
		err = deadlineErr
	}
	if err == nil {
		c.remember(key, body)
		body = append([]byte(nil), body...)
	} else {
		body = nil
	}
	// Release admission only after actual backend completion and validation.
	// No handler cancellation or timer can admit another physical backend read.
	c.mu.Lock()
	fill.body, fill.err = body, err
	delete(c.flights, key)
	close(fill.done)
	c.mu.Unlock()
}

func (c *cachedObjects) Probe(ctx context.Context) error { return c.backend.Probe(ctx) }
