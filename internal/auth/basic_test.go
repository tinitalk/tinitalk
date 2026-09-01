package auth

import (
	"path/filepath"
	"testing"

	"tinitalk/internal/state"
)

func TestBasicAuthenticatorAcceptsOnlyActiveEnabledTokens(t *testing.T) {
	db, err := state.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(); err != nil {
		t.Fatal(err)
	}
	aliceToken, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("bob", "Bob"); err != nil {
		t.Fatal(err)
	}

	a := NewBasicAuthenticator(db)
	user, ok := a.Authenticate("alice", aliceToken)
	if !ok || user.Login != "alice" {
		t.Fatalf("Authenticate(alice) = %+v, %v", user, ok)
	}
	if _, ok := a.Authenticate("bob", aliceToken); ok {
		t.Fatal("Authenticate accepted another user's token")
	}
	rotated, err := db.RotateToken("alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := a.Authenticate("alice", aliceToken); ok {
		t.Fatal("Authenticate accepted rotated token")
	}
	if _, ok := a.Authenticate("alice", rotated); !ok {
		t.Fatal("Authenticate rejected new token")
	}
	if err := db.DisableUser("alice"); err != nil {
		t.Fatal(err)
	}
	if _, ok := a.Authenticate("alice", rotated); ok {
		t.Fatal("Authenticate accepted disabled user")
	}
}
