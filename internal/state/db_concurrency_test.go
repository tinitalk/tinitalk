package state

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func TestWALFilesKeepDatabasePrivate(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permission bits")
	}
	dir := t.TempDir()
	if err := os.Chmod(dir, 0711); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	for _, suffix := range []string{"", "-wal", "-shm"} {
		info, err := os.Stat(path + suffix)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm()&0077 != 0 {
			t.Errorf("database file %s has non-private mode %o", suffix, info.Mode().Perm())
		}
	}
}

func TestReaderPoolDoesNotBlockWriterAndRejectsWrites(t *testing.T) {
	db := testStateDB(t)
	if _, err := db.AddUser("alice", "Before"); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var readers []*sql.Tx
	for range 4 {
		tx, err := db.read.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
		if err != nil {
			t.Fatal(err)
		}
		defer tx.Rollback()
		var name string
		if err := tx.QueryRow("SELECT display_name FROM users WHERE login = 'alice'").Scan(&name); err != nil || name != "Before" {
			t.Fatalf("initial snapshot: %q, %v", name, err)
		}
		readers = append(readers, tx)
	}
	// All four reader connections are occupied; writing must still commit.
	if err := db.RenameUser("alice", "After"); err != nil {
		t.Fatal(err)
	}
	for _, tx := range readers {
		var name string
		if err := tx.QueryRow("SELECT display_name FROM users WHERE login = 'alice'").Scan(&name); err != nil || name != "Before" {
			t.Fatalf("snapshot changed mid-read: %q, %v", name, err)
		}
		if _, err := tx.Exec("DELETE FROM users"); err == nil {
			t.Fatal("reader allowed a write")
		}
		if err := tx.Rollback(); err != nil {
			t.Fatal(err)
		}
	}
	// Force replacement connections: read-only restrictions and durability must survive.
	db.read.SetMaxIdleConns(0)
	if _, err := db.read.Exec("DELETE FROM users"); err == nil {
		t.Fatal("replacement reader allowed a write")
	}
	db.sql.SetMaxIdleConns(0)
	var synchronous int
	if err := db.sql.QueryRow("PRAGMA synchronous").Scan(&synchronous); err != nil || synchronous != 2 {
		t.Fatalf("replacement writer durability: %d, %v", synchronous, err)
	}
	users, err := db.ListUsers()
	if err != nil || len(users) != 1 || users[0].DisplayName != "After" {
		t.Fatalf("new read did not see committed data: %+v, %v", users, err)
	}
}

func TestBackupIncludesCommittedWALAndIsStandalone(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.sql.Exec("PRAGMA wal_checkpoint(TRUNCATE)"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	wal, err := os.Stat(path + "-wal")
	if err != nil || wal.Size() == 0 {
		t.Fatalf("fixture must contain uncheckpointed data: %v", err)
	}
	// The admin command opens the same live database independently.
	admin, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	backupPath := filepath.Join(t.TempDir(), "backup.db")
	if err := admin.BackupTo(backupPath); err != nil {
		t.Fatal(err)
	}
	files, err := os.ReadDir(filepath.Dir(backupPath))
	if err != nil || len(files) != 1 {
		t.Fatalf("backup depends on sidecar files: %v, %v", files, err)
	}
	if err := db.DeleteUser("alice"); err != nil {
		t.Fatal(err)
	}
	backup, err := Open(backupPath)
	if err != nil {
		t.Fatal(err)
	}
	defer backup.Close()
	users, err := backup.ListUsers()
	if err != nil || len(users) != 1 || users[0].Login != "alice" {
		t.Fatalf("backup lost WAL data: %+v, %v", users, err)
	}
}

func TestOpenConvertsExistingDeleteJournalDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state #1.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := legacy.Exec("PRAGMA journal_mode=DELETE"); err != nil {
		t.Fatal(err)
	}
	if err := legacy.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	users, err := db.ListUsers()
	if err != nil || len(users) != 1 || users[0].Login != "alice" {
		t.Fatalf("upgrade lost data: %+v, %v", users, err)
	}
	pragmas, err := db.Pragmas()
	if err != nil || pragmas["journal_mode"] != "wal" {
		t.Fatalf("upgrade did not enable WAL: %v, %v", pragmas, err)
	}
}

func TestReadsDoNotWaitForUncommittedWrite(t *testing.T) {
	db := testStateDB(t)
	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("bob", "Bob"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddContact("alice", "bob", "Before"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ClaimSession("alice", "phone"); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordBusyCall("busy", "bob", "alice", time.Now()); err != nil {
		t.Fatal(err)
	}

	tx, err := db.sql.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	if _, err := tx.Exec("UPDATE user_contacts SET custom_name = 'After'"); err != nil {
		t.Fatal(err)
	}

	done := make(chan error, 1)
	go func() {
		contacts, err := db.ContactsForUser("alice")
		if err != nil {
			done <- err
			return
		}
		if len(contacts) != 1 || contacts[0].DisplayName != "Before" {
			done <- fmt.Errorf("uncommitted contact visible: %+v", contacts)
			return
		}
		if _, ok, err := db.Authenticate("alice", token); err != nil || !ok {
			done <- fmt.Errorf("authentication: %v, %v", ok, err)
			return
		}
		if session, managed, err := db.CurrentSession("alice"); err != nil || !managed || session.DeviceID != "phone" {
			done <- fmt.Errorf("session: %+v, %v", session, err)
			return
		}
		page, err := db.CallHistory("alice", 0, 10)
		if err != nil {
			done <- err
			return
		}
		if len(page.Items) != 1 || page.UnreadMissed != 1 {
			done <- fmt.Errorf("history: %+v", page)
			return
		}
		done <- nil
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		_ = tx.Rollback()
		<-done
		t.Fatal("reads waited for an unrelated write transaction")
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	contact, err := db.ContactForUser("alice", "bob")
	if err != nil || contact.DisplayName != "After" {
		t.Fatalf("committed change not visible: %+v, %v", contact, err)
	}
}

func TestAdminWriteWaitsForAnotherConnection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	server, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	if _, err := server.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	admin, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	tx, err := server.sql.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	if _, err := tx.Exec("UPDATE users SET display_name = 'Updated'"); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- admin.DisableUser("alice") }()
	select {
	case err := <-done:
		t.Fatalf("competing writer must wait, got %v", err)
	case <-time.After(100 * time.Millisecond):
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(7 * time.Second):
		t.Fatal("writer did not resume")
	}
	users, err := server.ListUsers()
	if err != nil || len(users) != 1 || users[0].DisplayName != "Updated" || !users[0].Disabled {
		t.Fatalf("concurrent writes: %+v, %v", users, err)
	}
}

func TestHistoryPageKeepsUnreadCountConsistentDuringWrites(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Now()
	done := make(chan error, 1)
	go func() {
		for i := range 30 {
			if err := db.RecordBusyCall(fmt.Sprintf("parallel-%d", i), "alice", "bob", started); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	// Every record here is an unread incoming call; rows and badge must describe
	// the same snapshot even if a writer commits between the SELECT statements.
	for range 60 {
		page, err := db.CallHistory("bob", 0, 100)
		if err != nil {
			t.Error(err)
			break
		}
		if len(page.Items) != page.UnreadMissed || page.LatestID != int64(len(page.Items)) {
			t.Errorf("inconsistent history snapshot: rows=%d unread=%d latest=%d", len(page.Items), page.UnreadMissed, page.LatestID)
			break
		}
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
