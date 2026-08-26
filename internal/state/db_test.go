package state

import (
	"os"
	"path/filepath"
	"testing"
)

func TestOpenReopensWithRequiredPragmasAndStableFiles(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.db")

	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Init([]byte(`{"project_id":"demo"}`)); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	pragmas, err := db.Pragmas()
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{
		"journal_mode": "delete",
		"synchronous":  "3",
		"locking_mode": "normal",
		"foreign_keys": "1",
	}
	for key, value := range want {
		if pragmas[key] != value {
			t.Fatalf("pragma %s = %q, want %q", key, pragmas[key], value)
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "state.db" {
		t.Fatalf("database directory entries = %v, want only state.db", names(entries))
	}
}

func TestUsersTokensAndRollback(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(nil); err != nil {
		t.Fatal(err)
	}

	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(token) < 40 {
		t.Fatalf("token length = %d, want a generated secret", len(token))
	}
	if _, err := db.AddUser("alice", "Alice Again"); err == nil {
		t.Fatal("duplicate AddUser error = nil, want rejection")
	}
	users, err := db.ListUsers()
	if err != nil {
		t.Fatal(err)
	}
	if len(users) != 1 || users[0].Login != "alice" || users[0].Disabled {
		t.Fatalf("users = %+v, want one enabled alice", users)
	}
	if err := db.DisableUser("alice"); err != nil {
		t.Fatal(err)
	}
	rotated, err := db.RotateToken("alice")
	if err != nil {
		t.Fatal(err)
	}
	if rotated == token {
		t.Fatal("RotateToken returned the old token")
	}
	users, err = db.ListUsers()
	if err != nil {
		t.Fatal(err)
	}
	if !users[0].Disabled {
		t.Fatalf("users = %+v, want alice disabled", users)
	}
}

func TestDeleteUserRemovesCredentialsAndDevices(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(nil); err != nil {
		t.Fatal(err)
	}

	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertDevice("alice", "phone", "fcm-token"); err != nil {
		t.Fatal(err)
	}
	if err := db.DeleteUser("alice"); err != nil {
		t.Fatal(err)
	}

	users, err := db.ListUsers()
	if err != nil {
		t.Fatal(err)
	}
	if len(users) != 0 {
		t.Fatalf("users = %+v, want none", users)
	}
	if _, ok, err := db.Authenticate("alice", token); err != nil || ok {
		t.Fatalf("Authenticate after delete = %v, %v, want rejected", ok, err)
	}
	devices, err := db.TokensForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 0 {
		t.Fatalf("devices = %+v, want none", devices)
	}
	if err := db.DeleteUser("alice"); err == nil {
		t.Fatal("second DeleteUser error = nil, want user not found")
	}
}

func names(entries []os.DirEntry) []string {
	out := make([]string, 0, len(entries))
	for _, entry := range entries {
		out = append(out, entry.Name())
	}
	return out
}
