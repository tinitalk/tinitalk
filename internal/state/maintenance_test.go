package state

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestBackupVerificationFailureKeepsPrivatePermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permission bits are not supported on Windows")
	}
	db := testStateDB(t)
	// A real foreign-key violation makes validation fail after VACUUM has
	// already exported the database, including any credentials it contains.
	for _, query := range []string{
		"CREATE TABLE backup_parent (id INTEGER PRIMARY KEY)",
		"CREATE TABLE backup_child (parent_id INTEGER REFERENCES backup_parent(id))",
		"PRAGMA foreign_keys=OFF",
		"INSERT INTO backup_child VALUES (1)",
		"PRAGMA foreign_keys=ON",
	} {
		if _, err := db.sql.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	path := filepath.Join(t.TempDir(), "backup.db")
	if err := db.BackupTo(path); err == nil {
		t.Fatal("backup with a foreign-key violation must fail validation")
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() == 0 {
		t.Fatal("fixture must reach validation with an exported database")
	}
	if got := info.Mode().Perm(); got != 0600 {
		t.Fatalf("failed backup permissions = %04o, want 0600", got)
	}
}

func TestBackupDoesNotOverwriteExistingFile(t *testing.T) {
	db := testStateDB(t)
	for _, contents := range []string{"", "existing backup"} {
		t.Run(contents, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "backup.db")
			if err := os.WriteFile(path, []byte(contents), 0600); err != nil {
				t.Fatal(err)
			}
			if err := db.BackupTo(path); err == nil {
				t.Fatal("backup must reject an existing file, even if empty")
			}
			got, err := os.ReadFile(path)
			if err != nil || string(got) != contents {
				t.Fatalf("existing file changed: %q, %v", got, err)
			}
		})
	}
}

func TestBackupRejectsDanglingSymlink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("creating symlinks requires special privileges on Windows")
	}
	db := testStateDB(t)
	dir := t.TempDir()
	target := filepath.Join(dir, "target.db")
	path := filepath.Join(dir, "backup.db")
	if err := os.Symlink(target, path); err != nil {
		t.Fatal(err)
	}
	if err := db.BackupTo(path); err == nil {
		t.Fatal("backup must not follow a pre-existing symlink")
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("backup wrote through symlink: %v", err)
	}
}
