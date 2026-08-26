package notify

import (
	"encoding/json"
	"testing"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
)

func TestWakeMessageUsesHighPriorityAndShortTTL(t *testing.T) {
	msg := WakeMessage("project-1", "token-1", signaling.DeliveredEvent{
		Event: protocol.Event{CallID: "call-1", Type: "call.incoming", SentAt: 1000, Payload: json.RawMessage(`{"caller":"Alice"}`)},
		Seq:   1,
	}, "alice", "Alice", 30*time.Second)

	if msg.Message.Token != "token-1" {
		t.Fatalf("token = %q", msg.Message.Token)
	}
	if msg.Message.Android.Priority != "HIGH" || msg.Message.Android.TTL != "30s" {
		t.Fatalf("android = %+v", msg.Message.Android)
	}
	if msg.Message.Data["call_id"] != "call-1" || msg.Message.Data["type"] != "incoming_call" {
		t.Fatalf("data = %+v", msg.Message.Data)
	}
	if msg.Message.Data["last_seq"] != "1" {
		t.Fatalf("last_seq = %q", msg.Message.Data["last_seq"])
	}
	if msg.Message.Data["caller_login"] != "alice" {
		t.Fatalf("caller_login = %q", msg.Message.Data["caller_login"])
	}
}

func TestNotifierKeepsCallWhenSendFailsAndDisablesInvalidToken(t *testing.T) {
	store := &fakeTokenStore{tokens: []DeviceToken{{Token: "bad-token"}}, displayName: "Мама"}
	sender := &fakeSender{err: ErrInvalidRegistration}
	notifier := NewFCMNotifier(store, sender, "project-1")

	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})

	if sender.calls != 1 {
		t.Fatalf("send calls = %d", sender.calls)
	}
	if store.disabled != "bad-token" {
		t.Fatalf("disabled = %q", store.disabled)
	}
	if sender.last.Message.Data["caller"] != "Мама" {
		t.Fatalf("caller = %q", sender.last.Message.Data["caller"])
	}
	if store.nameOwner != "bob" || store.nameContact != "alice" {
		t.Fatalf("name lookup = owner %q, contact %q", store.nameOwner, store.nameContact)
	}
	if sender.last.Message.Data["caller_login"] != "alice" {
		t.Fatalf("caller_login = %q", sender.last.Message.Data["caller_login"])
	}
}

func TestNotifierSendsCallCancellation(t *testing.T) {
	store := &fakeTokenStore{tokens: []DeviceToken{{Token: "token-1"}}}
	sender := &fakeSender{}
	notifier := NewFCMNotifier(store, sender, "project-1")

	notifier.CancelCall("bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})

	if sender.last.Message.Data["type"] != "call_cancel" || sender.last.Message.Data["call_id"] != "call-1" {
		t.Fatalf("data = %+v", sender.last.Message.Data)
	}
}

func TestCancelMessageIncludesSignalingEventType(t *testing.T) {
	msg := CancelMessage("project", "token", signaling.DeliveredEvent{
		Event: protocol.Event{CallID: "call-1", Type: "call.accept"},
	}, 30*time.Second)
	if got := msg.Message.Data["call_event"]; got != "call.accept" {
		t.Fatalf("call_event = %q", got)
	}
}

func TestProjectIDFromServiceAccount(t *testing.T) {
	project, err := ProjectIDFromServiceAccount([]byte(`{"project_id":"example-project"}`))
	if err != nil {
		t.Fatal(err)
	}
	if project != "example-project" {
		t.Fatalf("project = %q", project)
	}
}

type fakeTokenStore struct {
	tokens      []DeviceToken
	disabled    string
	displayName string
	nameOwner   string
	nameContact string
}

func (s *fakeTokenStore) TokensForUser(string) ([]DeviceToken, error) { return s.tokens, nil }
func (s *fakeTokenStore) ContactDisplayName(owner, contact string) (string, error) {
	s.nameOwner = owner
	s.nameContact = contact
	return s.displayName, nil
}
func (s *fakeTokenStore) DisableToken(token string) error {
	s.disabled = token
	return nil
}

type fakeSender struct {
	calls int
	err   error
	last  WakeRequest
}

func (s *fakeSender) Send(request WakeRequest) error {
	s.calls++
	s.last = request
	return s.err
}
