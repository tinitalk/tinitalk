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
	if err := db.Init(nil, nil); err != nil {
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

func TestVersionSixMigrationPreservesLegacyPushRegistrations(t *testing.T) {
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
		INSERT INTO users(id, login, display_name) VALUES
			(11, 'alice', 'Alice'),
			(22, 'bob', 'Bob');
		INSERT INTO devices(id, user_id, device_id, fcm_token, updated_at) VALUES
			(101, 11, 'alice-phone', 'alice-token', 1700000001),
			(202, 22, 'bob-tablet', 'bob-token', 1700000002);
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
	if check.UserVersion != 8 {
		t.Fatalf("schema version = %d, want 8", check.UserVersion)
	}
	rows, err := db.sql.Query(`
		SELECT id, user_id, device_id, push_kind, push_value, config_id, updated_at
		FROM devices ORDER BY id
	`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	want := []struct {
		id, userID, updatedAt int64
		deviceID, kind, value string
	}{
		{101, 11, 1700000001, "alice-phone", "token", "alice-token"},
		{202, 22, 1700000002, "bob-tablet", "token", "bob-token"},
	}
	for _, expected := range want {
		if !rows.Next() {
			t.Fatalf("missing migrated device %+v", expected)
		}
		var got struct {
			id, userID, updatedAt int64
			deviceID, kind, value string
			configID              sql.NullString
		}
		if err := rows.Scan(&got.id, &got.userID, &got.deviceID, &got.kind, &got.value, &got.configID, &got.updatedAt); err != nil {
			t.Fatal(err)
		}
		if got.id != expected.id || got.userID != expected.userID || got.deviceID != expected.deviceID || got.kind != expected.kind || got.value != expected.value || got.configID.Valid || got.updatedAt != expected.updatedAt {
			t.Fatalf("migrated device = %+v, want %+v with null config ID", got, expected)
		}
	}
	if rows.Next() {
		t.Fatal("migrated more devices than legacy rows")
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
}

func TestUsersTokensAndRollback(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(nil, nil); err != nil {
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
	if err := db.Init(nil, nil); err != nil {
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
	devices, err := db.PushTargetsForUser("alice")
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

func TestRotateTokenClearsPushRegistrationAndPreservesManagedSession(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	claim, err := db.ClaimSession("alice", "phone")
	if err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertAuthenticatedDevice("alice", claim.Current.SessionID, "phone", "fcm-token"); err != nil {
		t.Fatal(err)
	}

	if _, err := db.RotateToken("alice"); err != nil {
		t.Fatal(err)
	}

	devices, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 0 {
		t.Fatalf("devices after token rotation = %+v, want none", devices)
	}
	current, managed, err := db.CurrentSession("alice")
	if err != nil {
		t.Fatal(err)
	}
	if !managed || current.DeviceID != claim.Current.DeviceID || current.SessionID != claim.Current.SessionID {
		t.Fatalf("session after token rotation = %+v, managed %v, want unchanged %+v", current, managed, claim.Current)
	}
}

func TestDisabledUserPushRegistrationResumesAfterEnable(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertDevice("alice", "phone", "fcm-token"); err != nil {
		t.Fatal(err)
	}
	if err := db.DisableUser("alice"); err != nil {
		t.Fatal(err)
	}

	devices, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 0 {
		t.Fatalf("disabled user devices = %+v, want none eligible for push", devices)
	}
	if err := db.EnableUser("alice"); err != nil {
		t.Fatal(err)
	}
	devices, err = db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].DeviceID != "phone" || devices[0].PushTarget != (PushTarget{Kind: KindToken, Value: "fcm-token"}) {
		t.Fatalf("enabled user devices = %+v, want preserved phone registration", devices)
	}
}

func TestPushTargetUpsertTransfersDeviceAndTargetOwnership(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("bob", "Bob"); err != nil {
		t.Fatal(err)
	}
	token := PushTarget{Kind: KindToken, Value: "shared-token"}
	if err := db.UpsertPushTarget("alice", "alice-phone", token); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertPushTarget("bob", "bob-tablet", token); err != nil {
		t.Fatal(err)
	}
	fid := PushTarget{Kind: KindFID, Value: "bob-fid", ConfigID: "firebase-config-a"}
	if err := db.UpsertPushTarget("alice", "bob-tablet", fid); err != nil {
		t.Fatal(err)
	}
	transferredFID := PushTarget{Kind: KindFID, Value: "bob-fid", ConfigID: "firebase-config-b"}
	if err := db.UpsertPushTarget("bob", "bob-phone", transferredFID); err != nil {
		t.Fatal(err)
	}

	alice, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	bob, err := db.PushTargetsForUser("bob")
	if err != nil {
		t.Fatal(err)
	}
	if len(alice) != 0 {
		t.Fatalf("alice targets = %+v, want none after FID ownership transfer", alice)
	}
	if len(bob) != 1 || bob[0].DeviceID != "bob-phone" || bob[0].PushTarget != transferredFID {
		t.Fatalf("bob targets = %+v, want transferred FID with its new configuration", bob)
	}
}

func TestPushTargetRequiresSupportedKindAndConfiguration(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	for _, target := range []PushTarget{
		{Kind: "other", Value: "value"},
		{Kind: KindFID, Value: "fid"},
		{Kind: KindToken, Value: "token", ConfigID: "firebase-config"},
	} {
		if err := db.UpsertPushTarget("alice", "phone", target); err == nil {
			t.Fatalf("UpsertPushTarget(%+v) error = nil, want rejected target", target)
		}
	}
}

func TestDisablePushTargetOnlyDisablesExactTargetAndUpsertReenablesIt(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	token := PushTarget{Kind: KindToken, Value: "shared-value"}
	fid := PushTarget{Kind: KindFID, Value: "shared-value", ConfigID: "firebase-config-a"}
	if err := db.UpsertPushTarget("alice", "phone", token); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertPushTarget("alice", "tablet", fid); err != nil {
		t.Fatal(err)
	}
	if err := db.DisablePushTarget(PushTarget{Kind: KindFID, Value: "shared-value", ConfigID: "firebase-config-b"}); err != nil {
		t.Fatal(err)
	}

	targets, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(targets) != 1 || targets[0].PushTarget != token {
		t.Fatalf("targets after FID disable = %+v, want only token with the same value", targets)
	}
	if err := db.UpsertPushTarget("alice", "tablet", PushTarget{Kind: KindFID, Value: "shared-value", ConfigID: "firebase-config-b"}); err != nil {
		t.Fatal(err)
	}
	targets, err = db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(targets) != 2 {
		t.Fatalf("targets after authenticated-style upsert = %+v, want re-enabled FID", targets)
	}
}

func names(entries []os.DirEntry) []string {
	out := make([]string, 0, len(entries))
	for _, entry := range entries {
		out = append(out, entry.Name())
	}
	return out
}
