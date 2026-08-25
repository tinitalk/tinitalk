package state

import "database/sql"

func (db *DB) Get(ctxKey string) ([]byte, error) {
	var value []byte
	if err := db.sql.QueryRow("SELECT value FROM autocert_cache WHERE key = ?", ctxKey).Scan(&value); err != nil {
		if err == sql.ErrNoRows {
			return nil, sql.ErrNoRows
		}
		return nil, err
	}
	return value, nil
}

func (db *DB) Put(ctxKey string, value []byte) error {
	_, err := db.sql.Exec(`
		INSERT INTO autocert_cache(key, value) VALUES(?, ?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=unixepoch()
	`, ctxKey, value)
	return err
}

func (db *DB) Delete(ctxKey string) error {
	_, err := db.sql.Exec("DELETE FROM autocert_cache WHERE key = ?", ctxKey)
	return err
}
