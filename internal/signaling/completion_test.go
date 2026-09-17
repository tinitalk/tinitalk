package signaling

import (
	"errors"
	"testing"
	"time"

	"tinitalk/internal/state"
)

type failingFinishHistory struct {
	CallHistoryStore
	fail     bool
	attempts int
}

func (s *failingFinishHistory) FinishCall(id string, outcome state.CallOutcome, at time.Time) error {
	s.attempts++
	if s.fail {
		return errors.New("history unavailable")
	}
	return s.CallHistoryStore.FinishCall(id, outcome, at)
}

func TestForcedCompletionRetriesHistoryWithoutRepeatingTermination(t *testing.T) {
	for _, kind := range []string{"timeout", "disconnect", "session replacement"} {
		t.Run(kind, func(t *testing.T) {
			hub, db, now := historyHub(t)
			store := &failingFinishHistory{CallHistoryStore: db, fail: true}
			hub.SetCallHistoryStore(store)
			start := event(uuid(9001), uuid(9002), "call.start", map[string]any{"callee_id": "bob"})
			if err := hub.Handle("alice", start); err != nil {
				t.Fatal(err)
			}
			want := state.CallOutcomeUnreachable
			if kind != "timeout" {
				if err := hub.Handle("bob", event(uuid(9003), start.CallID, "call.accept", map[string]any{})); err != nil {
					t.Fatal(err)
				}
				if err := hub.Handle("alice", event(uuid(9004), start.CallID, "call.connected", map[string]any{})); err != nil {
					t.Fatal(err)
				}
				want = state.CallOutcomeInterrupted
			}
			*now = now.Add(time.Minute)
			if kind == "session replacement" {
				hub.ReplaceSession("alice", "new-session")
			} else {
				hub.Sweep()
			}
			if active, err := hub.ActiveCall("alice"); err == nil || active != "" {
				t.Fatalf("failed history write kept participants busy: %+v, %v", active, err)
			}
			page, err := db.CallHistory("bob", 0, 10)
			if err != nil || len(page.Items) != 0 {
				t.Fatalf("history before recovery: %+v, %v", page, err)
			}
			old := hub.calls[start.CallID]
			seq := old.nextSeq
			newStart := event(uuid(9011), uuid(9012), "call.start", map[string]any{"callee_id": "bob"})
			if err := hub.Handle("alice", newStart); err != nil {
				t.Fatal(err)
			}
			store.fail = false
			*now = now.Add(10 * time.Second)
			hub.Sweep()
			page, err = db.CallHistory("bob", 0, 10)
			if err != nil || len(page.Items) != 1 {
				t.Fatalf("history after recovery: %+v, %v", page, err)
			}
			if page.Items[0].Outcome != want {
				t.Fatalf("outcome = %v", page.Items[0].Outcome)
			}
			if kind != "timeout" && page.Items[0].DurationSeconds != 60 {
				t.Fatalf("retry changed duration: %+v", page.Items[0])
			}
			if kind == "timeout" && page.UnreadMissed != 1 {
				t.Fatalf("missed count = %d", page.UnreadMissed)
			}
			if old.nextSeq != seq {
				t.Fatal("retry generated another terminal event")
			}
			if active, err := hub.ActiveCall("alice"); err != nil || active != newStart.CallID {
				t.Fatal("retry ended the new call")
			}
			hub.Sweep()
			if store.attempts != 2 {
				t.Fatalf("successful write retried: %d attempts", store.attempts)
			}
		})
	}
}

func TestForcedHistoryRetriesAreBoundedByCallRetention(t *testing.T) {
	hub, db, now := historyHub(t)
	store := &failingFinishHistory{CallHistoryStore: db, fail: true}
	hub.SetCallHistoryStore(store)
	start := event(uuid(9021), uuid(9022), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	*now = now.Add(time.Minute)
	hub.Sweep()
	for range 10 {
		hub.Sweep()
	}
	if store.attempts != 1 {
		t.Fatal("retrying without delay")
	}
	for range 121 {
		*now = now.Add(time.Second)
		hub.Sweep()
	}
	if store.attempts < 2 || store.attempts > 10 {
		t.Fatalf("unbounded or missing retries: %d", store.attempts)
	}
	if _, ok := hub.calls[start.CallID]; ok {
		t.Fatal("failed history retained call forever")
	}
	attempts := store.attempts
	*now = now.Add(time.Hour)
	hub.Sweep()
	if store.attempts != attempts {
		t.Fatal("retry after retention expired")
	}
}
