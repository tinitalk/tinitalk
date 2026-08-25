package state

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
	statements := []string{
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
		`PRAGMA user_version = 1`,
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	for _, statement := range statements {
		if _, err := tx.Exec(statement); err != nil {
			_ = tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}
