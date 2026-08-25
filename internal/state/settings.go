package state

import (
	"database/sql"
	"errors"
)

func (db *DB) SetSetting(key, value string) error {
	_, err := db.sql.Exec(`
		INSERT INTO settings(key, value) VALUES(?, ?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value
	`, key, value)
	return err
}

func (db *DB) Setting(key string) (string, error) {
	var value string
	err := db.sql.QueryRow("SELECT value FROM settings WHERE key = ?", key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return value, err
}
