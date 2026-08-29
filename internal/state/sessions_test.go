package state

import (
	"errors"
	"path/filepath"
	"testing"
)

func TestVersionSixMigrationLeavesExistingAccountLegacy(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertDevice("alice", "old-phone", "old-fcm"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("DROP TABLE IF EXISTS account_sessions"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("PRAGMA user_version = 5"); err != nil {
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
	check, err := db.Check()
	if err != nil {
		t.Fatal(err)
	}
	if check.UserVersion != 6 {
		t.Fatalf("schema version = %d, want 6", check.UserVersion)
	}
	var sessions int
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM account_sessions").Scan(&sessions); err != nil {
		t.Fatal(err)
	}
	if sessions != 0 {
		t.Fatalf("migrated sessions = %d, want empty legacy state", sessions)
	}
	if _, err := db.sql.Exec(`
		INSERT INTO account_sessions(user_id, device_id, session_id)
		SELECT id, '', 'invalid-empty-device' FROM users WHERE login = 'alice'
	`); err == nil {
		t.Fatal("account_sessions accepted an empty device_id")
	}
	devices, err := db.TokensForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].DeviceID != "old-phone" || devices[0].FCMToken != "old-fcm" {
		t.Fatalf("legacy devices after migration = %+v, want old-phone registration preserved", devices)
	}
}

func TestClaimSessionAtomicallyReplacesPriorSessionAndDevices(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertDevice("alice", "old-phone", "old-fcm"); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertDevice("alice", "new-phone", "new-fcm"); err != nil {
		t.Fatal(err)
	}

	first, err := db.ClaimSession("alice", "old-phone")
	if err != nil {
		t.Fatal(err)
	}
	if first.Current.SessionID == "" || first.Current.DeviceID != "old-phone" || first.Previous != nil || !first.Changed {
		t.Fatalf("first claim = %+v, want generated legacy-to-managed session on old-phone", first)
	}
	if len(first.RevokedDevices) != 1 || first.RevokedDevices[0].DeviceID != "new-phone" || first.RevokedDevices[0].FCMToken != "new-fcm" {
		t.Fatalf("first revoked devices = %+v, want new-phone registration", first.RevokedDevices)
	}
	if err := db.UpsertDevice("alice", "new-phone", "new-fcm-2"); err != nil {
		t.Fatal(err)
	}

	second, err := db.ClaimSession("alice", "new-phone")
	if err != nil {
		t.Fatal(err)
	}
	if second.Current.SessionID == "" || second.Current.SessionID == first.Current.SessionID || second.Current.DeviceID != "new-phone" || !second.Changed {
		t.Fatalf("second current session = %+v, want a new opaque session on new-phone", second.Current)
	}
	if second.Previous == nil || second.Previous.SessionID != first.Current.SessionID || second.Previous.DeviceID != "old-phone" {
		t.Fatalf("second previous session = %+v, want first claim", second.Previous)
	}
	if len(second.RevokedDevices) != 1 || second.RevokedDevices[0].DeviceID != "old-phone" || second.RevokedDevices[0].FCMToken != "old-fcm" {
		t.Fatalf("second revoked devices = %+v, want old-phone registration", second.RevokedDevices)
	}
	current, managed, err := db.CurrentSession("alice")
	if err != nil {
		t.Fatal(err)
	}
	if !managed || current.SessionID != second.Current.SessionID || current.DeviceID != "new-phone" {
		t.Fatalf("stored current session = %+v, managed %v, want second claim", current, managed)
	}
	devices, err := db.TokensForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].DeviceID != "new-phone" || devices[0].FCMToken != "new-fcm-2" {
		t.Fatalf("devices after replacement = %+v, want only retained new-phone", devices)
	}

	retry, err := db.ClaimSession("alice", "new-phone")
	if err != nil {
		t.Fatal(err)
	}
	if retry.Changed || retry.Current.SessionID != second.Current.SessionID || retry.Previous != nil || len(retry.RevokedDevices) != 0 {
		t.Fatalf("same-device retry = %+v, want unchanged second claim", retry)
	}
}

func TestAuthenticatedDeviceUpsertRechecksLegacyTransitionInTransaction(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertAuthenticatedDevice("alice", "", "old-phone", "legacy-fcm"); err != nil {
		t.Fatalf("legacy authenticated upsert: %v", err)
	}
	claim, err := db.ClaimSession("alice", "tablet")
	if err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertAuthenticatedDevice("alice", "", "old-phone", "stale-fcm"); !errors.Is(err, ErrSessionReplaced) {
		t.Fatalf("post-claim legacy upsert error = %v, want ErrSessionReplaced", err)
	}
	if err := db.UpsertAuthenticatedDevice("alice", claim.Current.SessionID, "old-phone", "wrong-device-fcm"); !errors.Is(err, ErrSessionReplaced) {
		t.Fatalf("managed wrong-device upsert error = %v, want ErrSessionReplaced", err)
	}
	if err := db.UpsertAuthenticatedDevice("alice", claim.Current.SessionID, "tablet", "current-fcm"); err != nil {
		t.Fatalf("managed authenticated upsert: %v", err)
	}
	devices, err := db.TokensForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].DeviceID != "tablet" || devices[0].FCMToken != "current-fcm" {
		t.Fatalf("authenticated devices = %+v, want only current tablet", devices)
	}
}
