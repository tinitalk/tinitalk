package signaling

import (
	"path/filepath"
	"testing"
	"time"

	"tinitalk/internal/state"
)

func TestHubRecordsCallHistoryOutcomes(t *testing.T) {
	t.Run("cancel after ringing", func(t *testing.T) {
		hub, db, now := historyHub(t)
		start := event(uuid(2001), uuid(2002), "call.start", map[string]any{"callee_id": "bob"})
		if err := hub.Handle("alice", start); err != nil {
			t.Fatal(err)
		}
		if err := hub.Handle("bob", event(uuid(2003), start.CallID, "call.ringing", map[string]any{})); err != nil {
			t.Fatal(err)
		}
		*now = now.Add(time.Minute)
		if err := hub.Handle("alice", event(uuid(2004), start.CallID, "call.cancel", map[string]any{})); err != nil {
			t.Fatal(err)
		}

		page, err := db.CallHistory("bob", 0, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(page.Items) != 1 || page.Items[0].Outcome != state.CallOutcomeCancelledAfterRinging || page.UnreadMissed != 1 {
			t.Fatalf("callee history = %+v", page)
		}
	})

	t.Run("completed after media connection", func(t *testing.T) {
		hub, db, now := historyHub(t)
		start := event(uuid(2011), uuid(2012), "call.start", map[string]any{"callee_id": "bob"})
		if err := hub.Handle("alice", start); err != nil {
			t.Fatal(err)
		}
		if err := hub.Handle("bob", event(uuid(2013), start.CallID, "call.accept", map[string]any{})); err != nil {
			t.Fatal(err)
		}
		*now = now.Add(5 * time.Second)
		if err := hub.Handle("alice", event(uuid(2014), start.CallID, "call.connected", map[string]any{})); err != nil {
			t.Fatal(err)
		}
		*now = now.Add(time.Minute)
		if err := hub.Handle("bob", event(uuid(2015), start.CallID, "call.end", map[string]any{})); err != nil {
			t.Fatal(err)
		}

		page, err := db.CallHistory("alice", 0, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(page.Items) != 1 || page.Items[0].Outcome != state.CallOutcomeCompleted || page.Items[0].DurationSeconds != 60 {
			t.Fatalf("caller history = %+v", page.Items)
		}
	})

	t.Run("busy callee", func(t *testing.T) {
		hub, db, _ := historyHub(t)
		first := event(uuid(2021), uuid(2022), "call.start", map[string]any{"callee_id": "bob"})
		if err := hub.Handle("alice", first); err != nil {
			t.Fatal(err)
		}
		err := hub.Handle("carol", event(uuid(2023), uuid(2024), "call.start", map[string]any{"callee_id": "bob"}))
		if err != ErrCalleeBusy {
			t.Fatalf("busy error = %v", err)
		}
		page, err := db.CallHistory("carol", 0, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(page.Items) != 1 || page.Items[0].Outcome != state.CallOutcomeBusy {
			t.Fatalf("busy history = %+v", page.Items)
		}
	})

	t.Run("crossed calls share one record", func(t *testing.T) {
		hub, db, _ := historyHub(t)
		first := event(uuid(2031), uuid(2032), "call.start", map[string]any{
			"callee_id": "bob", "supports_cross_call": true,
		})
		if err := hub.Handle("alice", first); err != nil {
			t.Fatal(err)
		}
		reverse := event(uuid(2033), uuid(2034), "call.start", map[string]any{
			"callee_id": "alice", "supports_cross_call": true,
		})
		if err := hub.Handle("bob", reverse); err != nil {
			t.Fatal(err)
		}
		if err := hub.Handle("alice", event(uuid(2035), first.CallID, "call.end", map[string]any{})); err != nil {
			t.Fatal(err)
		}

		page, err := db.CallHistory("alice", 0, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(page.Items) != 1 || page.Items[0].CallID != first.CallID {
			t.Fatalf("crossed call history = %+v", page.Items)
		}
	})
}

func historyHub(t *testing.T) (*Hub, *state.DB, *time.Time) {
	t.Helper()
	db, err := state.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	for _, user := range []struct{ login, name string }{
		{"alice", "Alice"}, {"bob", "Bob"}, {"carol", "Carol"},
	} {
		if _, err := db.AddUser(user.login, user.name); err != nil {
			t.Fatal(err)
		}
	}
	now := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	hub.SetCallHistoryStore(db)
	return hub, db, &now
}
