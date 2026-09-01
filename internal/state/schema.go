package state

import "fmt"

const baseSchemaVersion = 9

const freshSchemaSQL = `
CREATE TABLE settings(
	key TEXT PRIMARY KEY,
	value TEXT NOT NULL
);

CREATE TABLE secrets(
	key TEXT PRIMARY KEY,
	value BLOB NOT NULL
);

CREATE TABLE users(
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	login TEXT NOT NULL UNIQUE,
	display_name TEXT NOT NULL,
	disabled INTEGER NOT NULL DEFAULT 0,
	created_at INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE TABLE auth_tokens(
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	token_sha256 TEXT NOT NULL UNIQUE,
	active INTEGER NOT NULL DEFAULT 1,
	created_at INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE TABLE devices(
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	device_id TEXT NOT NULL,
	webpush_subscription TEXT,
	webpush_config_id TEXT,
	updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
	UNIQUE(user_id, device_id),
	CHECK(
		(webpush_subscription IS NULL AND webpush_config_id IS NULL) OR
		(webpush_subscription IS NOT NULL AND webpush_subscription <> '' AND
			webpush_config_id IS NOT NULL AND webpush_config_id <> '')
	)
);

CREATE TABLE autocert_cache(
	key TEXT PRIMARY KEY,
	value BLOB NOT NULL,
	updated_at INTEGER NOT NULL DEFAULT (unixepoch())
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

CREATE INDEX call_history_by_caller ON call_history(caller_id, id DESC);
CREATE INDEX call_history_by_callee ON call_history(callee_id, id DESC);

CREATE TABLE call_history_reads(
	user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	through_id INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE user_contacts(
	owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	contact_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	custom_name TEXT,
	added_at INTEGER NOT NULL DEFAULT (unixepoch()),
	PRIMARY KEY(owner_user_id, contact_user_id),
	CHECK(owner_user_id <> contact_user_id)
);

CREATE TABLE call_history_unread(
	call_history_id INTEGER PRIMARY KEY REFERENCES call_history(id) ON DELETE CASCADE,
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX call_history_unread_by_user
	ON call_history_unread(user_id, call_history_id);

CREATE TABLE account_sessions(
	user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	device_id TEXT NOT NULL CHECK(device_id <> ''),
	session_id TEXT NOT NULL UNIQUE,
	updated_at INTEGER NOT NULL DEFAULT (unixepoch())
);
`

// schemaMigrations contains one SQL script per schema version.
// Index 0 migrates baseSchemaVersion to baseSchemaVersion+1.
var schemaMigrations = []string{}

func currentSchemaVersion() int {
	return baseSchemaVersion + len(schemaMigrations)
}

func (db *DB) initializeSchema() error {
	var version int
	if err := db.sql.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	currentVersion := currentSchemaVersion()
	if version == currentVersion {
		return nil
	}
	if version == 0 {
		return db.installFreshSchema()
	}
	if version < baseSchemaVersion || version > currentVersion {
		return unsupportedSchemaError(version)
	}
	return db.migrateSchema(version, baseSchemaVersion, schemaMigrations)
}

func (db *DB) installFreshSchema() error {
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	if _, err := tx.Exec(freshSchemaSQL); err != nil {
		_ = tx.Rollback()
		return err
	}
	if _, err := tx.Exec(fmt.Sprintf("PRAGMA user_version = %d", currentSchemaVersion())); err != nil {
		_ = tx.Rollback()
		return err
	}
	return tx.Commit()
}

func (db *DB) migrateSchema(version, baseVersion int, migrations []string) error {
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	for index := version - baseVersion; index < len(migrations); index++ {
		if _, err := tx.Exec(migrations[index]); err != nil {
			_ = tx.Rollback()
			return err
		}
		nextVersion := baseVersion + index + 1
		if _, err := tx.Exec(fmt.Sprintf("PRAGMA user_version = %d", nextVersion)); err != nil {
			_ = tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}
