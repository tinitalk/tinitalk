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

	if _, err := Open(path); err == nil || err.Error() != "database schema 8 is unsupported; expected 10" {
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

func TestFreshSchemaIncludesNullableCallReplyCode(t *testing.T) {
	db := testStateDB(t)
	columns, err := db.sql.Query("PRAGMA table_info(call_history)")
	if err != nil {
		t.Fatal(err)
	}
	defer columns.Close()
	found := false
	for columns.Next() {
		var cid, notNull, primaryKey int
		var name, columnType string
		var defaultValue any
		if err := columns.Scan(&cid, &name, &columnType, &notNull, &defaultValue, &primaryKey); err != nil {
			t.Fatal(err)
		}
		if name == "reply_code" {
			found = true
			if columnType != "TEXT" || notNull != 0 {
				t.Fatalf("reply_code type/nullability = %q/%d, want TEXT/nullable", columnType, notNull)
			}
		}
	}
	if err := columns.Err(); err != nil {
		t.Fatal(err)
	}
	if !found {
		t.Fatal("fresh call_history schema has no reply_code column")
	}
}

func TestOpenMigratesSchemaNineCallHistoryWithoutLosingRows(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	legacySchema := `
		CREATE TABLE users(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			login TEXT NOT NULL UNIQUE,
			display_name TEXT NOT NULL,
			disabled INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL DEFAULT (unixepoch())
		);
		CREATE TABLE call_history(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			call_id TEXT NOT NULL UNIQUE,
			caller_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			callee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			stage INTEGER NOT NULL DEFAULT 0,
			outcome INTEGER NOT NULL DEFAULT 0,
			started_at INTEGER NOT NULL,
			connected_at INTEGER,
			ended_at INTEGER,
			CHECK(caller_id <> callee_id)
		);
		INSERT INTO users(id, login, display_name) VALUES(1, 'alice', 'Alice'), (2, 'bob', 'Bob');
		INSERT INTO call_history(call_id, caller_id, callee_id, stage, outcome, started_at, ended_at)
		VALUES('legacy-call', 1, 2, 1, 4, 100, 120);
		PRAGMA user_version = 9;
	`
	if _, err := legacy.Exec(legacySchema); err != nil {
		legacy.Close()
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
	var version int
	if err := db.sql.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 10 {
		t.Fatalf("schema version = %d, want 10", version)
	}
	var callID string
	var replyCode sql.NullString
	if err := db.sql.QueryRow("SELECT call_id, reply_code FROM call_history").Scan(&callID, &replyCode); err != nil {
		t.Fatal(err)
	}
	if callID != "legacy-call" || replyCode.Valid {
		t.Fatalf("migrated call = %q, reply %+v; want preserved row with NULL reply", callID, replyCode)
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

func TestMigrateSchemaEnforcesForeignKeysAfterConnectionReplacement(t *testing.T) {
	db := testStateDB(t)
	db.sql.SetMaxIdleConns(0)
	version := currentSchemaVersion()
	migrations := []string{`
		INSERT INTO auth_tokens(user_id, token_sha256)
		VALUES(999, 'orphan');
	`}

	if err := db.migrateSchema(version, version, migrations); err == nil {
		t.Fatal("migrateSchema error = nil, want foreign key failure")
	}

	var orphanRows int
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM auth_tokens WHERE token_sha256 = 'orphan'").Scan(&orphanRows); err != nil {
		t.Fatal(err)
	}
	if orphanRows != 0 {
		t.Fatalf("orphan auth tokens after failed migration = %d, want 0", orphanRows)
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
