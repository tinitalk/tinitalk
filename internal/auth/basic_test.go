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
	user, ok, err := a.Authenticate("alice", aliceToken)
	if err != nil || !ok || user.Login != "alice" {
		t.Fatalf("Authenticate(alice) = %+v, %v, %v", user, ok, err)
	}
	if _, ok, err := a.Authenticate("bob", aliceToken); err != nil || ok {
		t.Fatalf("Authenticate with another user's token = %v, %v; want false, nil", ok, err)
	}
	rotated, err := db.RotateToken("alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok, err := a.Authenticate("alice", aliceToken); err != nil || ok {
		t.Fatalf("Authenticate with rotated token = %v, %v; want false, nil", ok, err)
	}
	if _, ok, err := a.Authenticate("alice", rotated); err != nil || !ok {
		t.Fatalf("Authenticate with new token = %v, %v; want true, nil", ok, err)
	}
	if err := db.DisableUser("alice"); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := a.Authenticate("alice", rotated); err != nil || ok {
		t.Fatalf("Authenticate with disabled user = %v, %v; want false, nil", ok, err)
	}
}
