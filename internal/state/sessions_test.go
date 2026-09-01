package state

import (
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
)

func TestMigrationLeavesExistingAccountWithoutLegacyPushOrSession(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, migration := range schemaMigrations[:5] {
		for _, statement := range migration {
			if _, err := legacy.Exec(statement); err != nil {
				t.Fatal(err)
			}
		}
	}
	if _, err := legacy.Exec(`
		INSERT INTO users(login, display_name) VALUES('alice', 'Alice');
		INSERT INTO devices(user_id, device_id, fcm_token) VALUES(1, 'old-phone', 'old-fcm');
		PRAGMA user_version = 5;
	`); err != nil {
		t.Fatal(err)
	}
	if err := legacy.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	check, err := db.Check()
	if err != nil {
		t.Fatal(err)
	}
	if check.UserVersion != 9 {
		t.Fatalf("schema version = %d, want 9", check.UserVersion)
	}
	if _, managed, err := db.CurrentSession("alice"); err != nil || managed {
		t.Fatalf("migrated session managed = %v, error %v, want no session", managed, err)
	}
	targets, err := db.PushTargetsForUser("alice")
	if err != nil || len(targets) != 0 {
		t.Fatalf("migrated targets = %+v, error %v, want none", targets, err)
	}
}

func TestClaimSessionAtomicallyReplacesPriorSessionAndDevices(t *testing.T) {
	db := testStateDB(t)
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	oldTarget := testPushTarget("old")
	newTarget := testPushTarget("new")
	if err := db.UpsertPushTarget("alice", "old-phone", oldTarget); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertPushTarget("alice", "new-phone", newTarget); err != nil {
		t.Fatal(err)
	}

	first, err := db.ClaimSession("alice", "old-phone")
	if err != nil {
		t.Fatal(err)
	}
	if first.Current.SessionID == "" || !first.Changed || len(first.RevokedDevices) != 1 || first.RevokedDevices[0].PushTarget != newTarget {
		t.Fatalf("first claim = %+v, want old-phone session revoking new target", first)
	}
	if err := db.UpsertPushTarget("alice", "new-phone", newTarget); err != nil {
		t.Fatal(err)
	}
	second, err := db.ClaimSession("alice", "new-phone")
	if err != nil {
		t.Fatal(err)
	}
	if !second.Changed || second.Previous == nil || second.Previous.SessionID != first.Current.SessionID {
		t.Fatalf("second claim = %+v, want replacement", second)
	}
	targets, err := db.PushTargetsForUser("alice")
	if err != nil || len(targets) != 1 || targets[0].DeviceID != "new-phone" || targets[0].PushTarget != newTarget {
		t.Fatalf("targets after replacement = %+v, error %v", targets, err)
	}
}

func TestClaimSessionWithPushTargetRefreshesAndRollsBackTogether(t *testing.T) {
	db := testStateDB(t)
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	first, err := db.ClaimSession("alice", "phone")
	if err != nil {
		t.Fatal(err)
	}
	refreshed := testPushTarget("refreshed")
	retry, err := db.ClaimSessionWithPushTarget("alice", "phone", &refreshed)
	if err != nil {
		t.Fatal(err)
	}
	if retry.Current.SessionID != first.Current.SessionID || retry.Changed {
		t.Fatalf("same-device retry = %+v, want unchanged session", retry)
	}
	if _, err := db.sql.Exec(`CREATE TRIGGER reject_session_replacement BEFORE UPDATE ON account_sessions BEGIN SELECT RAISE(ABORT, 'reject'); END`); err != nil {
		t.Fatal(err)
	}
	failed := testPushTarget("failed")
	if _, err := db.ClaimSessionWithPushTarget("alice", "tablet", &failed); err == nil {
		t.Fatal("replacement succeeded despite forced database failure")
	}
	targets, err := db.PushTargetsForUser("alice")
	if err != nil || len(targets) != 1 || targets[0].DeviceID != "phone" || targets[0].PushTarget != refreshed {
		t.Fatalf("targets after rollback = %+v, error %v", targets, err)
	}
}

func TestAuthenticatedPushUpsertRequiresCurrentSessionDevice(t *testing.T) {
	db := testStateDB(t)
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertAuthenticatedPushTarget("alice", "", "old-phone", testPushTarget("old")); err != nil {
		t.Fatal(err)
	}
	claim, err := db.ClaimSession("alice", "tablet")
	if err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertAuthenticatedPushTarget("alice", "", "old-phone", testPushTarget("stale")); !errors.Is(err, ErrSessionReplaced) {
		t.Fatalf("stale upsert error = %v, want ErrSessionReplaced", err)
	}
	current := testPushTarget("current")
	if err := db.UpsertAuthenticatedPushTarget("alice", claim.Current.SessionID, "tablet", current); err != nil {
		t.Fatal(err)
	}
	targets, _ := db.PushTargetsForUser("alice")
	if len(targets) != 1 || targets[0].DeviceID != "tablet" || targets[0].PushTarget != current {
		t.Fatalf("authenticated targets = %+v, want current tablet", targets)
	}
}
