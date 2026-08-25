package state

import "database/sql"

func (db *DB) Secret(key string) ([]byte, error) {
	var value []byte
	err := db.sql.QueryRow("SELECT value FROM secrets WHERE key = ?", key).Scan(&value)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return value, err
}
