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
	}, 30*time.Second)

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
}

func TestNotifierKeepsCallWhenSendFailsAndDisablesInvalidToken(t *testing.T) {
	store := &fakeTokenStore{tokens: []DeviceToken{{Token: "bad-token"}}}
	sender := &fakeSender{err: ErrInvalidRegistration}
	notifier := NewFCMNotifier(store, sender, "project-1")

	notifier.IncomingCall("bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})

	if sender.calls != 1 {
		t.Fatalf("send calls = %d", sender.calls)
	}
	if store.disabled != "bad-token" {
		t.Fatalf("disabled = %q", store.disabled)
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
	tokens   []DeviceToken
	disabled string
}

func (s *fakeTokenStore) TokensForUser(string) ([]DeviceToken, error) { return s.tokens, nil }
func (s *fakeTokenStore) DisableToken(token string) error {
	s.disabled = token
	return nil
}

type fakeSender struct {
	calls int
	err   error
}

func (s *fakeSender) Send(WakeRequest) error {
	s.calls++
	return s.err
}
