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

func TestOpenRejectsLegacySchemaWithoutModifyingIt(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("PRAGMA user_version = 8"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("PRAGMA journal_mode = WAL"); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	if _, err := Open(path); err == nil || err.Error() != "database schema 8 is unsupported; expected 9" {
		t.Fatalf("Open legacy schema error = %v", err)
	}
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer legacy.Close()
	var version int
	if err := legacy.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 8 {
		t.Fatalf("schema version after rejected open = %d, want 8", version)
	}
	var journalMode string
	if err := legacy.QueryRow("PRAGMA journal_mode").Scan(&journalMode); err != nil {
		t.Fatal(err)
	}
	if journalMode != "wal" {
		t.Fatalf("journal mode after rejected open = %q, want wal", journalMode)
	}
}

func TestMigrateSchemaAppliesPendingScriptsInOrder(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	sqlDB, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("PRAGMA user_version = 9"); err != nil {
		t.Fatal(err)
	}
	db := &DB{sql: sqlDB}
	migrations := []string{
		`CREATE TABLE migration_probe(value TEXT NOT NULL);`,
		`INSERT INTO migration_probe(value) VALUES('applied in order');`,
	}

	if err := db.migrateSchema(9, 9, migrations); err != nil {
		t.Fatal(err)
	}

	var version int
	if err := sqlDB.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 11 {
		t.Fatalf("schema version = %d, want 11", version)
	}
	var value string
	if err := sqlDB.QueryRow("SELECT value FROM migration_probe").Scan(&value); err != nil {
		t.Fatal(err)
	}
	if value != "applied in order" {
		t.Fatalf("migration probe = %q, want applied in order", value)
	}
}

func TestMigrateSchemaRollsBackEntireChainOnError(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	sqlDB, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("PRAGMA user_version = 9"); err != nil {
		t.Fatal(err)
	}
	db := &DB{sql: sqlDB}
	migrations := []string{
		`CREATE TABLE migration_probe(value TEXT NOT NULL);`,
		`INSERT INTO missing_table(value) VALUES('fail');`,
	}

	if err := db.migrateSchema(9, 9, migrations); err == nil {
		t.Fatal("migrateSchema error = nil, want failure")
	}

	var version int
	if err := sqlDB.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 9 {
		t.Fatalf("schema version after failed migration = %d, want 9", version)
	}
	var tables int
	if err := sqlDB.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'migration_probe'").Scan(&tables); err != nil {
		t.Fatal(err)
	}
	if tables != 0 {
		t.Fatalf("migration_probe tables after failed migration = %d, want 0", tables)
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
