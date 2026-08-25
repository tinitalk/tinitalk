package signaling

import (
	"encoding/json"
	"testing"

	"tinitalk/internal/protocol"
)

func TestHubRoutesAcceptRejectAndRejectsThirdParty(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")
	eve := hub.Connect("eve")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90101", "018f7d51-40a1-7bb5-a2d0-7e47f9180101", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	incoming := next(t, bob)
	if incoming.Type != "call.incoming" || incoming.Seq != 1 {
		t.Fatalf("incoming = %+v", incoming)
	}

	accept := event("018f7d51-3f90-7e63-b657-4a83a6a90102", start.CallID, "call.accept", map[string]any{})
	if err := hub.Handle("bob", accept); err != nil {
		t.Fatal(err)
	}
	if got := next(t, alice); got.Type != "call.accept" || got.Seq != 2 {
		t.Fatalf("alice event = %+v", got)
	}
	if err := hub.Handle("eve", event("018f7d51-3f90-7e63-b657-4a83a6a90103", start.CallID, "rtc.ice", map[string]any{})); err == nil {
		t.Fatal("third party Handle error = nil, want rejection")
	}
	if got, ok := eve.TryNext(); ok {
		t.Fatalf("eve received event: %+v", got)
	}
}

func TestHubDeduplicatesAndReplaysAfterSequence(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90201", "018f7d51-40a1-7bb5-a2d0-7e47f9180201", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	if got, ok := bob.TryNext(); ok {
		t.Fatalf("duplicate delivered: %+v", got)
	}

	if err := hub.Handle("bob", event("018f7d51-3f90-7e63-b657-4a83a6a90202", start.CallID, "call.ringing", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	if err := hub.Handle("bob", event("018f7d51-3f90-7e63-b657-4a83a6a90203", start.CallID, "call.reject", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	replayed, err := hub.Resume("alice", start.CallID, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(replayed) != 2 || replayed[0].Type != "call.ringing" || replayed[1].Type != "call.reject" {
		t.Fatalf("replayed = %+v", replayed)
	}
	if got := next(t, alice); got.Type != "call.ringing" {
		t.Fatalf("alice first event = %+v", got)
	}
	if got := next(t, alice); got.Type != "call.reject" {
		t.Fatalf("alice second event = %+v", got)
	}
}

func event(id, callID, typ string, payload map[string]any) protocol.Event {
	raw, _ := json.Marshal(payload)
	return protocol.Event{
		ID:      id,
		CallID:  callID,
		Type:    typ,
		SentAt:  1787666400000,
		Payload: raw,
	}
}

func next(t *testing.T, c *Client) DeliveredEvent {
	t.Helper()
	got, ok := c.TryNext()
	if !ok {
		t.Fatal("no event delivered")
	}
	return got
}
