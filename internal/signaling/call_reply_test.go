package signaling

import (
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"tinitalk/internal/state"
)

func TestCallRepliesSurviveCompleteSignalingAndDatabaseReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "replies.db")
	db, err := state.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	for _, login := range []string{"alice", "bob"} {
		if _, err := db.AddUser(login, login); err != nil {
			t.Fatal(err)
		}
	}
	hub := NewHub(NoopNotifier{})
	hub.SetCallHistoryStore(db)
	alice, bob := hub.Connect("alice"), hub.Connect("bob")
	codes := []string{"cannot_talk", "call_me_later", "will_call_back"}
	for i, code := range codes {
		start := event(uuid(4400+i*10), uuid(4401+i*10), "call.start", map[string]any{"callee_id": "bob"})
		if err := hub.Handle("alice", start); err != nil {
			t.Fatal(err)
		}
		if incoming := next(t, bob); incoming.Type != "call.incoming" {
			t.Fatalf("incoming: %+v", incoming)
		}
		reject := event(uuid(4402+i*10), start.CallID, "call.reject", map[string]any{"reply_code": code})
		if err := hub.Handle("bob", reject); err != nil {
			t.Fatal(err)
		}
		if received := next(t, alice); string(received.Payload) != string(reject.Payload) {
			t.Fatalf("reply: %+v", received)
		}
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := state.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	for _, login := range []string{"alice", "bob"} {
		page, err := reopened.CallHistory(login, 0, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(page.Items) != len(codes) {
			t.Fatalf("history after reopen: %+v", page)
		}
		for i, item := range page.Items {
			if item.ReplyCode != codes[len(codes)-1-i] || item.Outcome != state.CallOutcomeRejected {
				t.Fatalf("reply after reopen for %s: %+v", login, item)
			}
		}
	}
}

type failingReplyHistory struct {
	CallHistoryStore
	fail bool
}

func (s *failingReplyHistory) FinishCallWithReply(id string, outcome state.CallOutcome, at time.Time, code string) error {
	if s.fail {
		return errors.New("history unavailable")
	}
	return s.CallHistoryStore.FinishCallWithReply(id, outcome, at, code)
}

func TestCallReplyPersistenceFailureAllowsRetryWithoutPrematureDelivery(t *testing.T) {
	hub, db, _ := historyHub(t)
	store := &failingReplyHistory{CallHistoryStore: db, fail: true}
	hub.history = store
	alice := hub.Connect("alice")
	start := event(uuid(4301), uuid(4302), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	reject := event(uuid(4303), start.CallID, "call.reject", map[string]any{"reply_code": "cannot_talk"})
	if err := hub.Handle("bob", reject); err == nil {
		t.Fatal("failed persistence reported success")
	}
	if delivered, ok := alice.TryNext(); ok {
		t.Fatalf("delivered before persistence: %+v", delivered)
	}
	if _, err := hub.ActiveCall("alice"); err != nil {
		t.Fatal("failed persistence ended the call", err)
	}
	store.fail = false
	if err := hub.Handle("bob", reject); err != nil {
		t.Fatal(err)
	}
	if delivered := next(t, alice); delivered.ID != reject.ID {
		t.Fatalf("retry changed event: %+v", delivered)
	}
	page, err := db.CallHistory("alice", 0, 10)
	if err != nil || len(page.Items) != 1 || page.Items[0].ReplyCode != "cannot_talk" {
		t.Fatalf("retry did not persist: %+v, %v", page, err)
	}
}

func TestCallReplyPersistsAndReplaysWithoutRepeatingRejection(t *testing.T) {
	for _, code := range []string{"cannot_talk", "call_me_later", "will_call_back"} {
		t.Run(code, func(t *testing.T) {
			hub, db, _ := historyHub(t)
			alice := hub.Connect("alice")
			bob := hub.Connect("bob")
			start := event(uuid(4101), uuid(4102), "call.start", map[string]any{"callee_id": "bob"})
			if err := hub.Handle("alice", start); err != nil {
				t.Fatal(err)
			}
			next(t, bob)
			reject := event(uuid(4103), start.CallID, "call.reject", map[string]any{"reply_code": code})
			if err := hub.Handle("bob", reject); err != nil {
				t.Fatal(err)
			}
			delivered := next(t, alice)
			var payload struct {
				ReplyCode string `json:"reply_code"`
			}
			if err := json.Unmarshal(delivered.Payload, &payload); err != nil {
				t.Fatal(err)
			}
			if delivered.Type != "call.reject" || payload.ReplyCode != code {
				t.Fatalf("unexpected event: %+v", delivered)
			}
			for _, user := range []string{"alice", "bob"} {
				page, err := db.CallHistory(user, 0, 10)
				if err != nil {
					t.Fatal(err)
				}
				if len(page.Items) != 1 || page.Items[0].ReplyCode != code || page.Items[0].Outcome != state.CallOutcomeRejected || page.UnreadMissed != 0 {
					t.Fatalf("history %s: %+v", user, page)
				}
			}
			if err := hub.Handle("bob", reject); err != nil {
				t.Fatal(err)
			}
			if duplicate, ok := alice.TryNext(); ok {
				t.Fatalf("duplicate action: %+v", duplicate)
			}
			if err := hub.Handle("alice", event(uuid(4104), start.CallID, "call.resume", map[string]any{"last_seq": 0})); err != nil {
				t.Fatal(err)
			}
			replay := next(t, alice)
			if replay.ID != reject.ID || string(replay.Payload) != string(reject.Payload) {
				t.Fatalf("replay lost reply: %+v", replay)
			}
			if _, err := hub.ActiveCall("alice"); err == nil {
				t.Fatal("reply left call active")
			}
		})
	}
}

func TestCallReplyAfterCancelDoesNotChangeHistory(t *testing.T) {
	hub, db, _ := historyHub(t)
	start := event(uuid(4201), uuid(4202), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	if err := hub.Handle("alice", event(uuid(4203), start.CallID, "call.cancel", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	if err := hub.Handle("bob", event(uuid(4204), start.CallID, "call.reject", map[string]any{"reply_code": "will_call_back"})); err == nil {
		t.Fatal("late reply succeeded")
	}
	page, err := db.CallHistory("alice", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].ReplyCode != "" || page.Items[0].Outcome != state.CallOutcomeCancelledBeforeRinging {
		t.Fatalf("late reply modified outcome: %+v", page)
	}
}

func TestCallReplyHistoryUsesExactWireField(t *testing.T) {
	for _, tc := range []struct {
		name    string
		payload string
		want    string
	}{
		{"uppercase_after", `{"reply_code":"cannot_talk","REPLY_CODE":"will_call_back"}`, "cannot_talk"},
		{"uppercase_before", `{"REPLY_CODE":"will_call_back","reply_code":"cannot_talk"}`, "cannot_talk"},
		{"unknown_field_only", `{"REPLY_CODE":"future_code"}`, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			hub, db, _ := historyHub(t)
			alice := hub.Connect("alice")
			start := event(uuid(4501), uuid(4502), "call.start", map[string]any{"callee_id": "bob"})
			if err := hub.Handle("alice", start); err != nil {
				t.Fatal(err)
			}
			reject := event(uuid(4503), start.CallID, "call.reject", map[string]any{})
			reject.Payload = json.RawMessage(tc.payload)
			if err := reject.Validate(); err != nil {
				t.Fatal(err)
			}
			if err := hub.Handle("bob", reject); err != nil {
				t.Fatal(err)
			}
			delivered := next(t, alice)
			var payload map[string]string
			if err := json.Unmarshal(delivered.Payload, &payload); err != nil {
				t.Fatal(err)
			}
			if delivered.Type != "call.reject" || payload["reply_code"] != tc.want {
				t.Fatalf("delivered reply = %+v, want %q", delivered, tc.want)
			}
			for _, login := range []string{"alice", "bob"} {
				page, err := db.CallHistory(login, 0, 10)
				if err != nil || len(page.Items) != 1 || page.Items[0].ReplyCode != tc.want || page.Items[0].Outcome != state.CallOutcomeRejected {
					t.Fatalf("history for %s = %+v, %v; want reply %q", login, page, err, tc.want)
				}
			}
		})
	}
}
