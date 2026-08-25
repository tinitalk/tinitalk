package tlsstore

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"

	"tinitalk/internal/state"
)

func TestCachePersistsAcrossReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := state.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	cache := NewCache(db)
	if err := cache.Put(context.Background(), "cert", []byte("bytes")); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()

	db, err = state.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	cache = NewCache(db)
	got, err := cache.Get(context.Background(), "cert")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "bytes" {
		t.Fatalf("cache Get = %q", got)
	}
	if err := cache.Delete(context.Background(), "cert"); err != nil {
		t.Fatal(err)
	}
	if _, err := cache.Get(context.Background(), "cert"); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("cache Get deleted error = %v, want sql.ErrNoRows", err)
	}
}
