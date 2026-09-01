package state

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestWebPushVAPIDIsGeneratedOnceAndPersists(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	first, err := db.EnsureWebPushVAPID()
	if err != nil {
		t.Fatal(err)
	}
	if first.PublicKey == "" || !strings.HasPrefix(first.ConfigID, "sha256:") {
		t.Fatalf("generated WebPush config = %+v, want public key and sha256 config ID", first)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	second, err := db.EnsureWebPushVAPID()
	if err != nil {
		t.Fatal(err)
	}
	if second.PublicKey != first.PublicKey || second.ConfigID != first.ConfigID {
		t.Fatalf("reopened WebPush config = %+v, want stable %+v", second, first)
	}
}
