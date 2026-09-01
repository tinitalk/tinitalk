package state

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"
)

type DB struct {
	sql *sql.DB
}

func Open(path string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", path+"?_foreign_keys=on")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	wrapped := &DB{sql: db}
	if err := wrapped.initializeSchema(); err != nil {
		_ = db.Close()
		return nil, err
	}
	if err := wrapped.configure(); err != nil {
		_ = db.Close()
		return nil, err
	}
	_ = os.Chmod(path, 0600)
	return wrapped, nil
}

func (db *DB) Close() error {
	return db.sql.Close()
}

func (db *DB) configure() error {
	pragmas := []string{
		"PRAGMA journal_mode=DELETE",
		"PRAGMA synchronous=EXTRA",
		"PRAGMA locking_mode=NORMAL",
	}
	for _, pragma := range pragmas {
		if _, err := db.sql.Exec(pragma); err != nil {
			return err
		}
	}
	return nil
}

func (db *DB) Pragmas() (map[string]string, error) {
	out := make(map[string]string)
	for _, key := range []string{"journal_mode", "synchronous", "locking_mode", "foreign_keys"} {
		var value string
		if err := db.sql.QueryRow("PRAGMA " + key).Scan(&value); err != nil {
			return nil, err
		}
		out[key] = value
	}
	return out, nil
}

func (db *DB) Init() error {
	return db.ensureSecret("turn_secret", randomToken)
}

func (db *DB) ensureSecret(key string, create func() (string, error)) error {
	var exists int
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM secrets WHERE key = ?", key).Scan(&exists); err != nil {
		return err
	}
	if exists > 0 {
		return nil
	}
	value, err := create()
	if err != nil {
		return err
	}
	_, err = db.sql.Exec("INSERT INTO secrets(key, value) VALUES(?, ?)", key, []byte(value))
	return err
}

func randomToken() (string, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw[:]), nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func stateDBPath(dataDir string) (string, error) {
	if dataDir == "" {
		return "", errors.New("data dir is required")
	}
	return filepath.Join(dataDir, "state.db"), nil
}

func OpenDir(dataDir string) (*DB, error) {
	path, err := stateDBPath(dataDir)
	if err != nil {
		return nil, err
	}
	return Open(path)
}

func unsupportedSchemaError(version int) error {
	return fmt.Errorf("database schema %d is unsupported; expected %d", version, currentSchemaVersion())
}
