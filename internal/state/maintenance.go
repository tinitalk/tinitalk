package state

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

type CheckResult struct {
	IntegrityOK  bool
	ForeignKeyOK bool
	UserVersion  int
	Pragmas      map[string]string
}

func (db *DB) PruneCallHistory(before time.Time) (int64, error) {
	if before.IsZero() {
		return 0, errors.New("call history cutoff is required")
	}
	result, err := db.sql.Exec("DELETE FROM call_history WHERE started_at < ?", before.Unix())
	if err != nil {
		return 0, err
	}
	deleted, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	if _, err := db.sql.Exec("VACUUM"); err != nil {
		return deleted, fmt.Errorf("deleted %d call history records but could not compact database: %w", deleted, err)
	}
	return deleted, nil
}

func (db *DB) Check() (CheckResult, error) {
	var result CheckResult
	var integrity string
	if err := db.read.QueryRow("PRAGMA integrity_check").Scan(&integrity); err != nil {
		return result, err
	}
	var foreignKeys string
	if err := db.read.QueryRow("PRAGMA foreign_key_check").Scan(&foreignKeys); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return result, err
	}
	if err := db.read.QueryRow("PRAGMA user_version").Scan(&result.UserVersion); err != nil {
		return result, err
	}
	pragmas, err := db.Pragmas()
	if err != nil {
		return result, err
	}
	result.IntegrityOK = integrity == "ok"
	result.ForeignKeyOK = foreignKeys == ""
	result.Pragmas = pragmas
	return result, nil
}

func (db *DB) BackupTo(path string) error {
	if path == "" {
		return errors.New("backup path is required")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	// VACUUM INTO accepts an empty file. Reserve it exclusively with private
	// permissions before SQLite writes secrets, including on failure paths.
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if errors.Is(err, os.ErrExist) {
		return errors.New("backup file already exists")
	}
	if err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	if _, err := db.sql.Exec("VACUUM INTO ?", path); err != nil {
		return err
	}
	// Validate the exported file without the server's WAL/pool bootstrap: the
	// backup must remain a standalone snapshot, not a second live database.
	uri, err := databaseURI(path)
	if err != nil {
		return err
	}
	uri.RawQuery = "mode=rw&_pragma=foreign_keys(ON)&_pragma=synchronous(FULL)"
	connection, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return err
	}
	connection.SetMaxOpenConns(1)
	defer connection.Close()
	backup := &DB{sql: connection, read: connection}
	check, err := backup.Check()
	if err != nil {
		return err
	}
	if !check.IntegrityOK || !check.ForeignKeyOK {
		return errors.New("backup verification failed")
	}
	var mode string
	if err := connection.QueryRow("PRAGMA journal_mode=DELETE").Scan(&mode); err != nil {
		return err
	}
	if mode != "delete" {
		return fmt.Errorf("backup journal mode = %q, want delete", mode)
	}
	if err := connection.Close(); err != nil {
		return err
	}
	return nil
}
