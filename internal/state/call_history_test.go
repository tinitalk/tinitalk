package state

import (
	"math"
	"path/filepath"
	"testing"
	"time"
)

func TestOpenMigratesVersionOneDatabaseWithoutLosingUsers(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("DROP TABLE IF EXISTS call_history_unread"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("DROP TABLE IF EXISTS user_contacts"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("DROP TABLE IF EXISTS call_history_reads"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("DROP TABLE IF EXISTS call_history"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("PRAGMA user_version = 1"); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	check, err := db.Check()
	if err != nil {
		t.Fatal(err)
	}
	if check.UserVersion != 3 {
		t.Fatalf("schema version = %d, want 3", check.UserVersion)
	}
	users, err := db.ListUsers()
	if err != nil {
		t.Fatal(err)
	}
	if len(users) != 1 || users[0].Login != "alice" {
		t.Fatalf("users after migration = %+v, want alice", users)
	}
	var historyRows int
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM call_history").Scan(&historyRows); err != nil {
		t.Fatal(err)
	}
}

func TestVersionThreeMigrationBackfillsOnlyUnreadMissedCalls(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, user := range []struct{ login, name string }{{"alice", "Alice"}, {"bob", "Bob"}} {
		if _, err := db.AddUser(user.login, user.name); err != nil {
			t.Fatal(err)
		}
	}
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	recordMissedCall(t, db, "read", started)
	if err := db.MarkCallHistoryRead("bob", math.MaxInt64); err != nil {
		t.Fatal(err)
	}
	recordMissedCall(t, db, "unread", started.Add(time.Hour))
	if _, err := db.sql.Exec("DROP TABLE IF EXISTS call_history_unread"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("DROP TABLE user_contacts"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("PRAGMA user_version = 2"); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var unreadRows int
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM call_history_unread").Scan(&unreadRows); err != nil {
		t.Fatal(err)
	}
	if unreadRows != 1 {
		t.Fatalf("migrated unread rows = %d, want 1", unreadRows)
	}
	var contactRows int
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM user_contacts").Scan(&contactRows); err != nil {
		t.Fatal(err)
	}
	if contactRows != 2 {
		t.Fatalf("migrated contact rows = %d, want 2", contactRows)
	}
}

func TestUnreadMissedRowsAreRemovedAfterHistoryIsRead(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	recordMissedCall(t, db, "missed", started)

	var unreadRows int
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM call_history_unread").Scan(&unreadRows); err != nil {
		t.Fatal(err)
	}
	if unreadRows != 1 {
		t.Fatalf("unread rows = %d, want 1", unreadRows)
	}
	if err := db.MarkCallHistoryRead("bob", math.MaxInt64); err != nil {
		t.Fatal(err)
	}
	if err := db.sql.QueryRow("SELECT COUNT(*) FROM call_history_unread").Scan(&unreadRows); err != nil {
		t.Fatal(err)
	}
	if unreadRows != 0 {
		t.Fatalf("unread rows after read = %d, want 0", unreadRows)
	}
}

func TestCallHistoryPagesNewestFirstAndCountsMissed(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	if err := db.SetContactName("bob", "alice", "Мама"); err != nil {
		t.Fatal(err)
	}
	older := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	newer := older.Add(time.Hour)

	if err := db.StartCall("missed", "alice", "bob", older); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallRinging("missed"); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("missed", CallOutcomeCancelledAfterRinging, older.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("talked", "alice", "bob", newer); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallAccepted("talked"); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallConnected("talked", newer.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("talked", CallOutcomeCompleted, newer.Add(3*time.Minute)); err != nil {
		t.Fatal(err)
	}

	page, err := db.CallHistory("bob", 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 {
		t.Fatalf("first page items = %d, want 1", len(page.Items))
	}
	latest := page.Items[0]
	if latest.CallID != "talked" || latest.Direction != CallDirectionIncoming || latest.PeerLogin != "alice" || latest.PeerName != "Мама" {
		t.Fatalf("latest item = %+v", latest)
	}
	if !latest.StartedAt.Equal(newer) || latest.DurationSeconds != 120 || latest.Outcome != CallOutcomeCompleted {
		t.Fatalf("latest timing/outcome = %+v", latest)
	}
	if page.UnreadMissed != 1 || page.NextBefore != latest.ID || page.LatestID != latest.ID {
		t.Fatalf("first page metadata = %+v", page)
	}

	page, err = db.CallHistory("bob", page.NextBefore, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].CallID != "missed" || page.Items[0].Outcome != CallOutcomeCancelledAfterRinging {
		t.Fatalf("second page = %+v", page)
	}
	if page.NextBefore != 0 || page.UnreadMissed != 1 {
		t.Fatalf("second page metadata = %+v", page)
	}
}

func TestCallHistoryReadMarkerCannotHideFutureMissedCalls(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)

	recordMissedCall(t, db, "missed-1", started)
	if err := db.MarkCallHistoryRead("bob", math.MaxInt64); err != nil {
		t.Fatal(err)
	}
	recordMissedCall(t, db, "missed-2", started.Add(time.Hour))
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if page.UnreadMissed != 1 {
		t.Fatalf("unread missed = %d, want 1", page.UnreadMissed)
	}
}

func TestRecordBusyCallIsCompleteAndIdempotent(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)

	if err := db.RecordBusyCall("busy", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordBusyCall("busy", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	page, err := db.CallHistory("alice", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].Outcome != CallOutcomeBusy {
		t.Fatalf("busy history = %+v", page.Items)
	}
}

func TestRecoverCallHistoryFinalizesUnfinishedCalls(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)

	if err := db.StartCall("not-answered", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("not-connected", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallAccepted("not-connected"); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("interrupted", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallAccepted("interrupted"); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallConnected("interrupted", started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.RecoverCallHistory(started.Add(3 * time.Minute)); err != nil {
		t.Fatal(err)
	}

	page, err := db.CallHistory("alice", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 3 {
		t.Fatalf("recovered items = %d, want 3", len(page.Items))
	}
	items := make(map[string]CallHistoryItem, len(page.Items))
	for _, item := range page.Items {
		items[item.CallID] = item
	}
	if items["not-answered"].Outcome != CallOutcomeInterruptedBeforeAnswer {
		t.Fatalf("not-answered = %+v", items["not-answered"])
	}
	if items["not-connected"].Outcome != CallOutcomeConnectionFailed {
		t.Fatalf("not-connected = %+v", items["not-connected"])
	}
	if items["interrupted"].Outcome != CallOutcomeInterrupted || items["interrupted"].DurationSeconds != 120 {
		t.Fatalf("interrupted = %+v", items["interrupted"])
	}
}

func TestDeleteUserRemovesSharedCallHistory(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)

	if err := db.StartCall("call-1", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("call-1", CallOutcomeUnreachable, started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.DeleteUser("alice"); err != nil {
		t.Fatal(err)
	}
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 0 {
		t.Fatalf("history after deleting alice = %+v, want empty", page.Items)
	}
}

func openCallHistoryTestDB(t *testing.T) *DB {
	t.Helper()
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	for _, user := range []struct{ login, name string }{{"alice", "Alice"}, {"bob", "Bob"}} {
		if _, err := db.AddUser(user.login, user.name); err != nil {
			db.Close()
			t.Fatal(err)
		}
	}
	return db
}

func recordMissedCall(t *testing.T, db *DB, callID string, started time.Time) {
	t.Helper()
	if err := db.StartCall(callID, "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallRinging(callID); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall(callID, CallOutcomeUnanswered, started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
}
