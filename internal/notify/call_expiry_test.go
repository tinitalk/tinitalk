package notify

import (
	"encoding/json"
	"testing"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func TestIncomingPushAndBothParticipantsShareCallExpiry(t *testing.T) {
	started := time.Date(2026, 9, 9, 15, 0, 0, 678_000_000, time.UTC)
	now := started
	sender := &expiryPushSender{requests: make(chan WebPushRequest, 2)}
	notifier := NewPushNotifier(&fakePushTargetStore{
		targets: []state.Device{{PushTarget: notifyTarget("phone")}},
	}, sender)
	hub := signaling.NewHub(notifier)
	hub.SetNow(func() time.Time { return now })
	caller := hub.Connect("alice")
	callee := hub.Connect("bob")
	start := protocol.Event{
		ID:      "018f7d51-3f90-7e63-b657-4a83a6a90501",
		CallID:  "018f7d51-40a1-7bb5-a2d0-7e47f9180501",
		Type:    "call.start",
		SentAt:  started.Add(-time.Minute).UnixMilli(), // The caller's clock is not authoritative.
		Payload: json.RawMessage(`{"callee_id":"bob"}`),
	}
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	if incoming, ok := callee.TryNext(); !ok || incoming.Type != "call.incoming" {
		t.Fatalf("incoming event = %+v, received = %v", incoming, ok)
	}
	wake := nextExpiryPush(t, sender)
	startedAt, err := time.Parse(time.RFC3339Nano, wake.Data["started_at"])
	if err != nil || !startedAt.Equal(started) {
		t.Fatalf("push start = %q, %v; want server start %s", wake.Data["started_at"], err, started)
	}
	expiresAt, err := time.Parse(time.RFC3339Nano, wake.Data["expires_at"])
	if err != nil {
		t.Fatal(err)
	}
	ringing := protocol.Event{
		ID:      "018f7d51-3f90-7e63-b657-4a83a6a90502",
		CallID:  start.CallID,
		Type:    "call.ringing",
		SentAt:  now.UnixMilli(),
		Payload: json.RawMessage(`{}`),
	}
	if err := hub.Handle("bob", ringing); err != nil {
		t.Fatal(err)
	}
	if delivered, ok := caller.TryNext(); !ok || delivered.Type != "call.ringing" {
		t.Fatalf("caller ringing event = %+v, received = %v", delivered, ok)
	}

	// Reproduce the reported gap: the receiver must still ring after 30 seconds
	// while the caller is waiting for the server's 45-second deadline.
	now = started.Add(30 * time.Second)
	if expired := hub.ExpireWaiting(); expired != 0 {
		t.Fatalf("server expired %d calls after only 30 seconds", expired)
	}
	if !expiresAt.After(now) {
		t.Fatalf("receiver expiry %s has passed while the caller is still waiting at %s", expiresAt, now)
	}
	if !expiresAt.Equal(started.Add(45 * time.Second)) {
		t.Fatalf("receiver expiry = %s, want the complete 45-second server window", expiresAt)
	}
	if wake.TTL < expiresAt.Sub(started) {
		t.Fatalf("push TTL %s is shorter than the ringing window", wake.TTL)
	}

	now = expiresAt.Add(-time.Millisecond)
	if expired := hub.ExpireWaiting(); expired != 0 {
		t.Fatalf("server expired %d calls before the receiver deadline", expired)
	}
	now = expiresAt
	if expired := hub.ExpireWaiting(); expired != 1 {
		t.Fatalf("server expired %d calls at the receiver deadline, want 1", expired)
	}
	for login, participant := range map[string]*signaling.Client{"alice": caller, "bob": callee} {
		ended, ok := participant.TryNext()
		if !ok || ended.Type != "call.expire" || ended.CallID != start.CallID {
			t.Fatalf("%s expiry event = %+v, received = %v", login, ended, ok)
		}
		if _, err := hub.ActiveCall(login); err == nil {
			t.Fatalf("%s still has an active call after expiry", login)
		}
	}
	cancel := nextExpiryPush(t, sender)
	if cancel.Data["type"] != "call_cancel" || cancel.Data["call_event"] != "call.expire" {
		t.Fatalf("expiry push = %+v", cancel.Data)
	}
}

type expiryPushSender struct {
	requests chan WebPushRequest
}

func (s *expiryPushSender) Send(request WebPushRequest) error {
	s.requests <- request
	return nil
}

func nextExpiryPush(t *testing.T, sender *expiryPushSender) WebPushRequest {
	t.Helper()
	select {
	case request := <-sender.requests:
		return request
	case <-time.After(time.Second):
		t.Fatal("push was not sent")
		return WebPushRequest{}
	}
}
