package state

import (
	"database/sql"
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
	if err := db.Init(); err != nil {
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
	want := map[string]string{"journal_mode": "delete", "synchronous": "3", "locking_mode": "normal", "foreign_keys": "1"}
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

func TestMigrationFromVersionSixDropsFCMRegistrations(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, migration := range schemaMigrations[:6] {
		for _, statement := range migration {
			if _, err := legacy.Exec(statement); err != nil {
				t.Fatal(err)
			}
		}
	}
	if _, err := legacy.Exec(`
		INSERT INTO users(id, login, display_name) VALUES(11, 'alice', 'Alice');
		INSERT INTO devices(id, user_id, device_id, fcm_token) VALUES(101, 11, 'alice-phone', 'alice-token');
		PRAGMA user_version = 6;
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
	targets, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(targets) != 0 {
		t.Fatalf("legacy FCM registrations survived migration: %+v", targets)
	}
}

func TestVersionNineMigrationKeepsOnlyWebPushRegistrations(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, migration := range schemaMigrations[:8] {
		for _, statement := range migration {
			if _, err := legacy.Exec(statement); err != nil {
				t.Fatal(err)
			}
		}
	}
	if _, err := legacy.Exec(`
		INSERT INTO users(id, login, display_name) VALUES(1, 'alice', 'Alice');
		INSERT INTO devices(user_id, device_id, push_kind, push_value, config_id) VALUES
			(1, 'token-phone', 'token', 'legacy-token', NULL),
			(1, 'fid-phone', 'fid', 'legacy-fid', 'firebase-config'),
			(1, 'webpush-phone', 'webpush', '{"endpoint":"https://push.example/subscription"}', 'sha256:webpush');
		INSERT INTO secrets(key, value) VALUES('fcm_service_account', 'legacy-service-account');
		INSERT INTO settings(key, value) VALUES('firebase_android_config', 'legacy-android-config');
		PRAGMA user_version = 8;
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
	targets, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	want := PushTarget{Subscription: `{"endpoint":"https://push.example/subscription"}`, ConfigID: "sha256:webpush"}
	if len(targets) != 1 || targets[0].DeviceID != "webpush-phone" || targets[0].PushTarget != want {
		t.Fatalf("push targets after migration = %+v, want only WebPush target %+v", targets, want)
	}
	for table, key := range map[string]string{"secrets": "fcm_service_account", "settings": "firebase_android_config"} {
		var count int
		if err := db.sql.QueryRow("SELECT COUNT(*) FROM "+table+" WHERE key = ?", key).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count != 0 {
			t.Fatalf("legacy key %s remains in %s", key, table)
		}
	}
}

func TestUsersTokensAndRollback(t *testing.T) {
	db := testStateDB(t)
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
}

func TestDeleteUserRemovesCredentialsAndDevices(t *testing.T) {
	db := testStateDB(t)
	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertPushTarget("alice", "phone", testPushTarget("phone")); err != nil {
		t.Fatal(err)
	}
	if err := db.DeleteUser("alice"); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := db.Authenticate("alice", token); err != nil || ok {
		t.Fatalf("Authenticate after delete = %v, %v, want rejected", ok, err)
	}
	targets, err := db.PushTargetsForUser("alice")
	if err != nil || len(targets) != 0 {
		t.Fatalf("devices after delete = %+v, %v, want none", targets, err)
	}
}

func TestRotateTokenClearsPushRegistrationAndPreservesManagedSession(t *testing.T) {
	db := testStateDB(t)
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	claim, err := db.ClaimSession("alice", "phone")
	if err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertAuthenticatedPushTarget("alice", claim.Current.SessionID, "phone", testPushTarget("phone")); err != nil {
		t.Fatal(err)
	}
	if _, err := db.RotateToken("alice"); err != nil {
		t.Fatal(err)
	}
	targets, err := db.PushTargetsForUser("alice")
	if err != nil || len(targets) != 0 {
		t.Fatalf("devices after token rotation = %+v, %v, want none", targets, err)
	}
	current, managed, err := db.CurrentSession("alice")
	if err != nil || !managed || current.SessionID != claim.Current.SessionID {
		t.Fatalf("session after token rotation = %+v, managed %v, error %v", current, managed, err)
	}
}

func TestPushTargetUpsertTransfersOwnershipAndCanBeDisabled(t *testing.T) {
	db := testStateDB(t)
	for _, login := range []string{"alice", "bob"} {
		if _, err := db.AddUser(login, login); err != nil {
			t.Fatal(err)
		}
	}
	target := testPushTarget("shared")
	if err := db.UpsertPushTarget("alice", "alice-phone", target); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertPushTarget("bob", "bob-phone", target); err != nil {
		t.Fatal(err)
	}
	alice, _ := db.PushTargetsForUser("alice")
	bob, _ := db.PushTargetsForUser("bob")
	if len(alice) != 0 || len(bob) != 1 || bob[0].PushTarget != target {
		t.Fatalf("targets after ownership transfer: alice=%+v bob=%+v", alice, bob)
	}
	if err := db.DisablePushTarget(target); err != nil {
		t.Fatal(err)
	}
	bob, _ = db.PushTargetsForUser("bob")
	if len(bob) != 0 {
		t.Fatalf("targets after disable = %+v, want none", bob)
	}
}

func TestPushTargetRequiresSubscriptionAndConfiguration(t *testing.T) {
	db := testStateDB(t)
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	for _, target := range []PushTarget{{}, {Subscription: "subscription"}, {ConfigID: "config"}} {
		if err := db.UpsertPushTarget("alice", "phone", target); err == nil {
			t.Fatalf("UpsertPushTarget(%+v) error = nil, want rejection", target)
		}
	}
}

func testStateDB(t *testing.T) *DB {
	t.Helper()
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := db.Init(); err != nil {
		t.Fatal(err)
	}
	return db
}

func testPushTarget(id string) PushTarget {
	return PushTarget{Subscription: `{"endpoint":"https://push.example/` + id + `"}`, ConfigID: "sha256:webpush"}
}

func names(entries []os.DirEntry) []string {
	out := make([]string, 0, len(entries))
	for _, entry := range entries {
		out = append(out, entry.Name())
	}
	return out
}
