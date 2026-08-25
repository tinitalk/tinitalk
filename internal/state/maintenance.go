package state

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"
)

type CheckResult struct {
	IntegrityOK  bool
	ForeignKeyOK bool
	UserVersion  int
	Pragmas      map[string]string
}

func (db *DB) Check() (CheckResult, error) {
	var result CheckResult
	var integrity string
	if err := db.sql.QueryRow("PRAGMA integrity_check").Scan(&integrity); err != nil {
		return result, err
	}
	var foreignKeys string
	if err := db.sql.QueryRow("PRAGMA foreign_key_check").Scan(&foreignKeys); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return result, err
	}
	if err := db.sql.QueryRow("PRAGMA user_version").Scan(&result.UserVersion); err != nil {
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
	if _, err := os.Stat(path); err == nil {
		return errors.New("backup file already exists")
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	if _, err := db.sql.Exec("VACUUM INTO ?", path); err != nil {
		return err
	}
	backup, err := Open(path)
	if err != nil {
		return err
	}
	defer backup.Close()
	check, err := backup.Check()
	if err != nil {
		return err
	}
	if !check.IntegrityOK || !check.ForeignKeyOK {
		return errors.New("backup verification failed")
	}
	return nil
}
