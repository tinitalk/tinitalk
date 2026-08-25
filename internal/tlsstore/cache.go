package tlsstore

import (
	"context"

	"tinitalk/internal/state"
)

type Cache struct {
	db *state.DB
}

func NewCache(db *state.DB) *Cache {
	return &Cache{db: db}
}

func (c *Cache) Get(_ context.Context, key string) ([]byte, error) {
	return c.db.Get(key)
}

func (c *Cache) Put(_ context.Context, key string, data []byte) error {
	return c.db.Put(key, data)
}

func (c *Cache) Delete(_ context.Context, key string) error {
	return c.db.Delete(key)
}
