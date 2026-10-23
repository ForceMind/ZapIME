// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"container/list"
	"context"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"
)

// This disposable cache is only for the HTTP service. Backup/verification CLI
// commands use the underlying store directly, so they still check durable data.
// Authorization stays in Store before any cached object can be requested.
type cachedObjects struct {
	backend ObjectStore
	mu      sync.Mutex
	entries map[string]*list.Element
	lru     *list.List
	used    int
	limit   int
	ttl     time.Duration
	now     func() time.Time
	flights singleflight.Group
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
func NewCachedObjects(backend ObjectStore, maxBytes int, ttl time.Duration) ObjectStore {
	if backend == nil || maxBytes <= 0 || ttl <= 0 {
		return backend
	}
	return &cachedObjects{backend: backend, entries: make(map[string]*list.Element), lru: list.New(), limit: maxBytes, ttl: ttl, now: time.Now}
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
	if body, ok := c.lookup(key); ok {
		return body, nil
	}
	result := c.flights.DoChan(key, func() (any, error) {
		if body, ok := c.lookup(key); ok {
			return body, nil
		}
		// A disconnected first caller must not cancel the other waiting devices.
		// The shared fetch still has a fixed deadline and stores no failure result.
		fetchCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		body, err := c.backend.Get(fetchCtx, key)
		if err != nil {
			return nil, err
		}
		if !validObject(key, body) {
			return nil, errObject
		}
		c.remember(key, body)
		return body, nil
	})
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case r := <-result:
		if r.Err != nil {
			return nil, r.Err
		}
		return append([]byte(nil), r.Val.([]byte)...), nil
	}
}

func (c *cachedObjects) Probe(ctx context.Context) error { return c.backend.Probe(ctx) }
