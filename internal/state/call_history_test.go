package state

import (
	"fmt"
	"math"
	"path/filepath"
	"reflect"
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
	setMigrationTestVersion(t, db, 1)
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
	if check.UserVersion != 9 {
		t.Fatalf("schema version = %d, want 9", check.UserVersion)
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

func TestVersionFourMigrationBackfillsEveryPassiveMissedCall(t *testing.T) {
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
	outcomes := []CallOutcome{
		CallOutcomeUnreachable,
		CallOutcomeUnanswered,
		CallOutcomeCancelledBeforeRinging,
		CallOutcomeCancelledAfterRinging,
		CallOutcomeInterruptedBeforeAnswer,
	}
	for i, outcome := range outcomes {
		callID := fmt.Sprintf("missed-%d", i)
		if err := db.StartCall(callID, "alice", "bob", started.Add(time.Duration(i)*time.Minute)); err != nil {
			t.Fatal(err)
		}
		if err := db.FinishCall(callID, outcome, started.Add(time.Duration(i+1)*time.Minute)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.sql.Exec(`
		DELETE FROM call_history_unread
		WHERE call_history_id IN (
			SELECT id FROM call_history WHERE outcome IN (1, 5, 9)
		)
	`); err != nil {
		t.Fatal(err)
	}
	setMigrationTestVersion(t, db, 3)
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
	if unreadRows != len(outcomes) {
		t.Fatalf("migrated unread rows = %d, want %d", unreadRows, len(outcomes))
	}
}

func TestVersionFourMigrationDoesNotRestorePeerReadMissedCall(t *testing.T) {
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
	if err := db.StartCall("read-missed", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallRinging("read-missed"); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("read-missed", CallOutcomeUnanswered, started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := db.MarkCallHistoryReadForPeer("bob", "alice", math.MaxInt64); err != nil {
		t.Fatal(err)
	}
	setMigrationTestVersion(t, db, 3)
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if page.UnreadMissed != 0 {
		t.Fatalf("unread missed after migration = %d, want 0", page.UnreadMissed)
	}
}

func TestVersionFiveMigrationBackfillsUnreadBusyCallsOnly(t *testing.T) {
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
	if err := db.RecordBusyCall("read-busy", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallHistoryRead("bob", math.MaxInt64); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("rejected", "alice", "bob", started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("rejected", CallOutcomeRejected, started.Add(2*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordBusyCall("unread-busy", "alice", "bob", started.Add(3*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("DELETE FROM call_history_unread"); err != nil {
		t.Fatal(err)
	}
	setMigrationTestVersion(t, db, 4)
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var unreadRows int
	var unreadOutcome CallOutcome
	if err := db.sql.QueryRow(`
		SELECT COUNT(*), COALESCE(MAX(history.outcome), 0)
		FROM call_history_unread unread
		JOIN call_history history ON history.id = unread.call_history_id
	`).Scan(&unreadRows, &unreadOutcome); err != nil {
		t.Fatal(err)
	}
	if unreadRows != 1 || unreadOutcome != CallOutcomeBusy {
		t.Fatalf("migrated unread = %d outcome %d, want one busy", unreadRows, unreadOutcome)
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
	setMigrationTestVersion(t, db, 2)
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

func TestEveryUnansweredIncomingCallIsUnreadMissed(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	outcomes := []CallOutcome{
		CallOutcomeUnreachable,
		CallOutcomeUnanswered,
		CallOutcomeBusy,
		CallOutcomeCancelledBeforeRinging,
		CallOutcomeCancelledAfterRinging,
		CallOutcomeInterruptedBeforeAnswer,
	}
	for i, outcome := range outcomes {
		callID := fmt.Sprintf("passive-%d", i)
		if err := db.StartCall(callID, "alice", "bob", started.Add(time.Duration(i)*time.Minute)); err != nil {
			t.Fatal(err)
		}
		if err := db.FinishCall(callID, outcome, started.Add(time.Duration(i+1)*time.Minute)); err != nil {
			t.Fatal(err)
		}
	}
	if err := db.StartCall("rejected", "alice", "bob", started.Add(10*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("rejected", CallOutcomeRejected, started.Add(11*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("connection-failed", "alice", "bob", started.Add(12*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallAccepted("connection-failed"); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("connection-failed", CallOutcomeConnectionFailed, started.Add(13*time.Minute)); err != nil {
		t.Fatal(err)
	}

	page, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if page.UnreadMissed != len(outcomes) {
		t.Fatalf("unread missed = %d, want %d", page.UnreadMissed, len(outcomes))
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

func TestCallHistoryPagesByStartedAtThenID(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)

	// IDs are assigned in this order, intentionally different from the desired
	// started_at ordering. "same-newer-id" also verifies the ID tie-breaker.
	recordMissedCall(t, db, "oldest", started)
	recordMissedCall(t, db, "newest-low-id", started.Add(2*time.Hour))
	recordMissedCall(t, db, "same", started.Add(time.Hour))
	recordMissedCall(t, db, "same-newer-id", started.Add(time.Hour))

	first, err := db.CallHistory("bob", 0, 2)
	if err != nil {
		t.Fatal(err)
	}
	if got := []string{first.Items[0].CallID, first.Items[1].CallID}; !reflect.DeepEqual(got, []string{"newest-low-id", "same-newer-id"}) {
		t.Fatalf("first page call IDs = %v, want [newest-low-id same-newer-id]", got)
	}
	if first.NextBefore != first.Items[1].ID {
		t.Fatalf("first page next_before = %d, want last item ID %d", first.NextBefore, first.Items[1].ID)
	}

	second, err := db.CallHistory("bob", first.NextBefore, 2)
	if err != nil {
		t.Fatal(err)
	}
	if got := []string{second.Items[0].CallID, second.Items[1].CallID}; !reflect.DeepEqual(got, []string{"same", "oldest"}) {
		t.Fatalf("second page call IDs = %v, want [same oldest]", got)
	}
	if second.NextBefore != 0 {
		t.Fatalf("second page next_before = %d, want 0", second.NextBefore)
	}
}

func TestCallHistoryRejectsMissingOrOutOfScopeCursor(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	if _, err := db.AddUser("carol", "Carol"); err != nil {
		t.Fatal(err)
	}
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	recordMissedCall(t, db, "alice-call", started)
	recordMissedCallFrom(t, db, "carol-call", "carol", "bob", started.Add(time.Minute))

	var carolCallID int64
	if err := db.sql.QueryRow("SELECT id FROM call_history WHERE call_id = ?", "carol-call").Scan(&carolCallID); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name   string
		before int64
	}{
		{name: "missing", before: 999},
		{name: "different peer", before: carolCallID},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := db.CallHistoryForPeer("bob", "alice", test.before, 10); err == nil {
				t.Errorf("CallHistoryForPeer with %s cursor error = nil, want error", test.name)
			}
		})
	}
}

func TestCallHistoryReportsWhetherCalleeWasReached(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)

	if err := db.StartCall("offline", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("offline", CallOutcomeUnreachable, started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("rang", "alice", "bob", started.Add(2*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallRinging("rang"); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("rang", CallOutcomeUnanswered, started.Add(3*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordBusyCall("busy", "alice", "bob", started.Add(4*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("rejected", "alice", "bob", started.Add(5*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("rejected", CallOutcomeRejected, started.Add(6*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("accepted", "alice", "bob", started.Add(7*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallAccepted("accepted"); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("accepted", CallOutcomeConnectionFailed, started.Add(8*time.Minute)); err != nil {
		t.Fatal(err)
	}

	page, err := db.CallHistory("alice", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	reachedByCall := make(map[string]bool, len(page.Items))
	for _, item := range page.Items {
		reachedByCall[item.CallID] = item.Reached
	}
	want := map[string]bool{
		"offline":  false,
		"rang":     true,
		"busy":     true,
		"rejected": true,
		"accepted": true,
	}
	if len(reachedByCall) != len(want) {
		t.Fatalf("reached by call = %v, want %v", reachedByCall, want)
	}
	for callID, wantReached := range want {
		if got, ok := reachedByCall[callID]; !ok || got != wantReached {
			t.Fatalf("call %q reached = %v (present %v), want %v", callID, got, ok, wantReached)
		}
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

func TestCallHistoryForPeerFiltersAndMarksOnlyThatPeerRead(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	if _, err := db.AddUser("carol", "Carol"); err != nil {
		t.Fatal(err)
	}
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	recordMissedCallFrom(t, db, "alice-call", "alice", "bob", started)
	recordMissedCallFrom(t, db, "carol-call", "carol", "bob", started.Add(time.Hour))

	page, err := db.CallHistoryForPeer("bob", "alice", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].CallID != "alice-call" || page.Items[0].PeerLogin != "alice" {
		t.Fatalf("alice history = %+v", page.Items)
	}
	if page.UnreadMissed != 2 || page.LatestID != page.Items[0].ID {
		t.Fatalf("alice history metadata = %+v", page)
	}

	unread, err := db.MarkCallHistoryReadForPeer("bob", "alice", math.MaxInt64)
	if err != nil {
		t.Fatal(err)
	}
	if unread != 1 {
		t.Fatalf("unread after reading alice = %d, want 1", unread)
	}
	all, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if all.UnreadMissed != 1 {
		t.Fatalf("global unread after reading alice = %d, want 1", all.UnreadMissed)
	}
}

func TestCallHistoryIncludesLatestUnreadMissedForEachContact(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	if _, err := db.AddUser("carol", "Carol"); err != nil {
		t.Fatal(err)
	}
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	recordMissedCallFrom(t, db, "alice-old", "alice", "bob", started)
	recordMissedCallFrom(t, db, "carol", "carol", "bob", started.Add(time.Hour))
	recordMissedCallFrom(t, db, "alice-new", "alice", "bob", started.Add(2*time.Hour))

	page, err := db.CallHistory("bob", 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	want := []UnreadMissedContact{
		{PeerLogin: "alice", StartedAt: started.Add(2 * time.Hour)},
		{PeerLogin: "carol", StartedAt: started.Add(time.Hour)},
	}
	if len(page.LatestUnreadMissed) != len(want) {
		t.Fatalf("latest unread missed = %+v, want %+v", page.LatestUnreadMissed, want)
	}
	for i := range want {
		if page.LatestUnreadMissed[i].PeerLogin != want[i].PeerLogin ||
			!page.LatestUnreadMissed[i].StartedAt.Equal(want[i].StartedAt) {
			t.Fatalf("latest unread missed = %+v, want %+v", page.LatestUnreadMissed, want)
		}
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
	calleePage, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if calleePage.UnreadMissed != 1 {
		t.Fatalf("callee unread missed = %d, want 1", calleePage.UnreadMissed)
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

func TestRecoveryMarksAnEarlierActiveCallUnreadAfterLaterHistoryWasRead(t *testing.T) {
	db := openCallHistoryTestDB(t)
	defer db.Close()
	started := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	if err := db.StartCall("still-ringing", "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.StartCall("later-completed", "alice", "bob", started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallAccepted("later-completed"); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallConnected("later-completed", started.Add(2*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall("later-completed", CallOutcomeCompleted, started.Add(3*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallHistoryRead("bob", math.MaxInt64); err != nil {
		t.Fatal(err)
	}

	if err := db.RecoverCallHistory(started.Add(4 * time.Minute)); err != nil {
		t.Fatal(err)
	}
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if page.UnreadMissed != 1 {
		t.Fatalf("unread missed after recovery = %d, want 1", page.UnreadMissed)
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
	recordMissedCallFrom(t, db, callID, "alice", "bob", started)
}

func recordMissedCallFrom(t *testing.T, db *DB, callID, caller, callee string, started time.Time) {
	t.Helper()
	if err := db.StartCall(callID, caller, callee, started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallRinging(callID); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall(callID, CallOutcomeUnanswered, started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
}

func setMigrationTestVersion(t *testing.T, db *DB, version int) {
	t.Helper()
	if _, err := db.sql.Exec("DROP TABLE IF EXISTS account_sessions"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec(`
		DROP TABLE devices;
		CREATE TABLE devices(
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			device_id TEXT NOT NULL,
			fcm_token TEXT,
			updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
			UNIQUE(user_id, device_id)
		);
	`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec(fmt.Sprintf("PRAGMA user_version = %d", version)); err != nil {
		t.Fatal(err)
	}
}
