package state

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
)

type DB struct {
	// sql serializes writes, including reads inside write transactions.
	sql  *sql.DB
	read *sql.DB
}

func Open(path string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	uri, err := databaseURI(path)
	if err != nil {
		return nil, err
	}
	// DSN pragmas apply to every replacement connection, not just the first.
	options := url.Values{
		"_pragma": {"busy_timeout(5000)", "foreign_keys(ON)", "synchronous(FULL)"},
		"_txlock": {"immediate"},
	}
	uri.RawQuery = options.Encode()
	db, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	wrapped := &DB{sql: db}
	if err := wrapped.initializeSchema(); err != nil {
		_ = db.Close()
		return nil, err
	}
	// SQLite derives sidecar permissions from the database file. Restrict it
	// before enabling WAL or opening readers that may create the shared memory.
	if err := os.Chmod(path, 0600); err != nil {
		_ = db.Close()
		return nil, err
	}
	if err := wrapped.configure(); err != nil {
		_ = db.Close()
		return nil, err
	}
	options.Set("mode", "ro")
	options.Set("_txlock", "deferred")
	options.Add("_pragma", "query_only(ON)")
	uri.RawQuery = options.Encode()
	reader, err := sql.Open("sqlite", uri.String())
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	reader.SetMaxOpenConns(4)
	reader.SetMaxIdleConns(4)
	wrapped.read = reader
	if err := reader.Ping(); err != nil {
		_ = wrapped.Close()
		return nil, err
	}
	return wrapped, nil
}

func databaseURI(path string) (url.URL, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return url.URL{}, err
	}
	uriPath := filepath.ToSlash(absolute)
	if !strings.HasPrefix(uriPath, "/") {
		uriPath = "/" + uriPath
	}
	return url.URL{Scheme: "file", Path: uriPath}, nil
}

func (db *DB) Close() error {
	// Close readers before the writer so the last connection can checkpoint WAL.
	var readErr error
	if db.read != nil {
		readErr = db.read.Close()
	}
	return errors.Join(readErr, db.sql.Close())
}

func (db *DB) configure() error {
	// Validate/migrate the schema before changing the persistent journal mode.
	var mode string
	if err := db.sql.QueryRow("PRAGMA journal_mode=WAL").Scan(&mode); err != nil {
		return err
	}
	if mode != "wal" {
		return fmt.Errorf("SQLite WAL mode unavailable: got %q", mode)
	}
	return nil
}

func (db *DB) Pragmas() (map[string]string, error) {
	out := make(map[string]string)
	for _, key := range []string{"journal_mode", "synchronous", "locking_mode", "foreign_keys"} {
		var value string
		if err := db.read.QueryRow("PRAGMA " + key).Scan(&value); err != nil {
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
