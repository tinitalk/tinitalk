package state

import "fmt"

var schemaMigrations = [][]string{
	{
		`CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)`,
		`CREATE TABLE secrets(key TEXT PRIMARY KEY, value BLOB NOT NULL)`,
		`CREATE TABLE users(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			login TEXT NOT NULL UNIQUE,
			display_name TEXT NOT NULL,
			disabled INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL DEFAULT (unixepoch())
		)`,
		`CREATE TABLE auth_tokens(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			token_sha256 TEXT NOT NULL UNIQUE,
			active INTEGER NOT NULL DEFAULT 1,
			created_at INTEGER NOT NULL DEFAULT (unixepoch())
		)`,
		`CREATE TABLE devices(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			device_id TEXT NOT NULL,
			fcm_token TEXT,
			updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
			UNIQUE(user_id, device_id)
		)`,
		`CREATE TABLE autocert_cache(
			key TEXT PRIMARY KEY,
			value BLOB NOT NULL,
			updated_at INTEGER NOT NULL DEFAULT (unixepoch())
		)`,
	},
	{
		`CREATE TABLE call_history(
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
		)`,
		`CREATE INDEX call_history_by_caller ON call_history(caller_id, id DESC)`,
		`CREATE INDEX call_history_by_callee ON call_history(callee_id, id DESC)`,
		`CREATE TABLE call_history_reads(
			user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
			through_id INTEGER NOT NULL DEFAULT 0
		)`,
	},
	{
		`CREATE TABLE user_contacts(
			owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			contact_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			custom_name TEXT,
			added_at INTEGER NOT NULL DEFAULT (unixepoch()),
			PRIMARY KEY(owner_user_id, contact_user_id),
			CHECK(owner_user_id <> contact_user_id)
		)`,
		`INSERT INTO user_contacts(owner_user_id, contact_user_id)
		 SELECT owner.id, contact.id
		 FROM users owner, users contact
		 WHERE owner.id <> contact.id`,
		`CREATE TABLE call_history_unread(
			call_history_id INTEGER PRIMARY KEY REFERENCES call_history(id) ON DELETE CASCADE,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX call_history_unread_by_user
		 ON call_history_unread(user_id, call_history_id)`,
		`INSERT INTO call_history_unread(call_history_id, user_id)
		 SELECT history.id, history.callee_id
		 FROM call_history history
		 LEFT JOIN call_history_reads reads ON reads.user_id = history.callee_id
		 WHERE history.ended_at IS NOT NULL
			AND history.outcome IN (2, 6)
			AND history.id > COALESCE(reads.through_id, 0)`,
	},
	{
		`INSERT OR IGNORE INTO call_history_unread(call_history_id, user_id)
		 SELECT history.id, history.callee_id
		 FROM call_history history
		 LEFT JOIN call_history_reads reads ON reads.user_id = history.callee_id
		 WHERE history.ended_at IS NOT NULL
			AND history.outcome IN (1, 5, 9)
			AND history.id > COALESCE(reads.through_id, 0)`,
	},
	{
		`INSERT OR IGNORE INTO call_history_unread(call_history_id, user_id)
		 SELECT history.id, history.callee_id
		 FROM call_history history
		 LEFT JOIN call_history_reads reads ON reads.user_id = history.callee_id
		 WHERE history.ended_at IS NOT NULL
			AND history.outcome = 3
			AND history.id > COALESCE(reads.through_id, 0)`,
	},
	{
		`CREATE TABLE account_sessions(
			user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
			device_id TEXT NOT NULL CHECK(device_id <> ''),
			session_id TEXT NOT NULL UNIQUE,
			updated_at INTEGER NOT NULL DEFAULT (unixepoch())
		)`,
	},
	{
		`CREATE TABLE devices_new(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			device_id TEXT NOT NULL,
			push_kind TEXT,
			push_value TEXT,
			config_id TEXT,
			updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
			UNIQUE(user_id, device_id),
			CHECK(
				(push_kind IS NULL AND push_value IS NULL AND config_id IS NULL) OR
				(push_kind = 'token' AND push_value IS NOT NULL AND push_value <> '' AND config_id IS NULL) OR
				(push_kind = 'fid' AND push_value IS NOT NULL AND push_value <> '' AND config_id IS NOT NULL AND config_id <> '')
			)
		)`,
		`INSERT INTO devices_new(id, user_id, device_id, push_kind, push_value, config_id, updated_at)
		 SELECT id, user_id, device_id,
			CASE WHEN fcm_token IS NULL OR fcm_token = '' THEN NULL ELSE 'token' END,
			NULLIF(fcm_token, ''),
			NULL,
			updated_at
		 FROM devices`,
		`DROP TABLE devices`,
		`ALTER TABLE devices_new RENAME TO devices`,
	},
	{
		`CREATE TABLE devices_new(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			device_id TEXT NOT NULL,
			push_kind TEXT,
			push_value TEXT,
			config_id TEXT,
			updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
			UNIQUE(user_id, device_id),
			CHECK(
				(push_kind IS NULL AND push_value IS NULL AND config_id IS NULL) OR
				(push_kind = 'token' AND push_value IS NOT NULL AND push_value <> '' AND config_id IS NULL) OR
				(push_kind IN ('fid', 'webpush') AND push_value IS NOT NULL AND push_value <> '' AND config_id IS NOT NULL AND config_id <> '')
			)
		)`,
		`INSERT INTO devices_new(id, user_id, device_id, push_kind, push_value, config_id, updated_at)
		 SELECT id, user_id, device_id, push_kind, push_value, config_id, updated_at FROM devices`,
		`DROP TABLE devices`,
		`ALTER TABLE devices_new RENAME TO devices`,
	},
	{
		`CREATE TABLE devices_new(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			device_id TEXT NOT NULL,
			webpush_subscription TEXT,
			webpush_config_id TEXT,
			updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
			UNIQUE(user_id, device_id),
			CHECK(
				(webpush_subscription IS NULL AND webpush_config_id IS NULL) OR
				(webpush_subscription IS NOT NULL AND webpush_subscription <> '' AND webpush_config_id IS NOT NULL AND webpush_config_id <> '')
			)
		)`,
		`INSERT INTO devices_new(id, user_id, device_id, webpush_subscription, webpush_config_id, updated_at)
		 SELECT id, user_id, device_id, push_value, config_id, updated_at
		 FROM devices
		 WHERE push_kind = 'webpush'`,
		`DROP TABLE devices`,
		`ALTER TABLE devices_new RENAME TO devices`,
		`DELETE FROM secrets WHERE key = 'fcm_service_account'`,
		`DELETE FROM settings WHERE key = 'firebase_android_config'`,
	},
}

func (db *DB) migrate() error {
	var version int
	if err := db.sql.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	if version > schemaVersion {
		return newerSchemaError(version)
	}
	if version == schemaVersion {
		return nil
	}
	for version < schemaVersion {
		tx, err := db.sql.Begin()
		if err != nil {
			return err
		}
		for _, statement := range schemaMigrations[version] {
			if _, err := tx.Exec(statement); err != nil {
				_ = tx.Rollback()
				return err
			}
		}
		if _, err := tx.Exec(fmt.Sprintf("PRAGMA user_version = %d", version+1)); err != nil {
			_ = tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
		version++
	}
	return nil
}
