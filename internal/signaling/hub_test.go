package signaling

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"

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

func TestHubUsesServerTimeForIncomingCall(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	now := time.Unix(1787666400, 0)
	hub.SetNow(func() time.Time { return now })
	bob := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90111", "018f7d51-40a1-7bb5-a2d0-7e47f9180111", "call.start", map[string]any{"callee_id": "bob"})
	start.SentAt = 1
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	if incoming := next(t, bob); incoming.SentAt != now.UnixMilli() {
		t.Fatalf("incoming sent_at = %d, want %d", incoming.SentAt, now.UnixMilli())
	}
}

func TestHubSendsICEConfigToParticipantsAfterAccept(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	hub.SetICEConfigProvider(fakeICEConfig{})
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90601", "018f7d51-40a1-7bb5-a2d0-7e47f9180601", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	accept := event("018f7d51-3f90-7e63-b657-4a83a6a90602", start.CallID, "call.accept", map[string]any{})
	if err := hub.Handle("bob", accept); err != nil {
		t.Fatal(err)
	}

	if got := next(t, alice); got.Type != "call.accept" {
		t.Fatalf("alice accept = %+v", got)
	}
	if got := next(t, alice); got.Type != "rtc.config" {
		t.Fatalf("alice config = %+v", got)
	}
	if got := next(t, bob); got.Type != "rtc.config" {
		t.Fatalf("bob config = %+v", got)
	}
}

func TestHubSendsEmptyICEConfigWhenTURNIsDisabled(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")
	start := event("018f7d51-3f90-7e63-b657-4a83a6a91101", "018f7d51-40a1-7bb5-a2d0-7e47f9181101", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	if err := hub.Handle("bob", event("018f7d51-3f90-7e63-b657-4a83a6a91102", start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alice)
	for _, client := range []*Client{alice, bob} {
		config := next(t, client)
		if config.Type != "rtc.config" || string(config.Payload) != `{"ice_servers":[]}` {
			t.Fatalf("config = %+v", config)
		}
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

func TestHubHandlesResumeEventByReplayingToSender(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90301", "018f7d51-40a1-7bb5-a2d0-7e47f9180301", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	if err := hub.Handle("bob", event("018f7d51-3f90-7e63-b657-4a83a6a90302", start.CallID, "call.ringing", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	if err := hub.Handle("alice", event("018f7d51-3f90-7e63-b657-4a83a6a90303", start.CallID, "call.resume", map[string]any{"last_seq": 1})); err != nil {
		t.Fatal(err)
	}

	if got := next(t, alice); got.Type != "call.ringing" || got.Seq != 2 {
		t.Fatalf("alice replay = %+v", got)
	}
	if got, ok := bob.TryNext(); ok {
		t.Fatalf("bob received resume side effect: %+v", got)
	}
}

func TestHubCancelsEndsExpiresAndLimitsICE(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return time.Unix(1000, 0) })
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90401", "018f7d51-40a1-7bb5-a2d0-7e47f9180401", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	if err := hub.Handle("bob", event("018f7d51-3f90-7e63-b657-4a83a6a90402", start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alice)
	_ = next(t, alice)
	for i := 0; i < MaxICEPerMinute; i++ {
		if err := hub.Handle("alice", event(uuid(500+i), start.CallID, "rtc.ice", map[string]any{"candidate": "candidate"})); err != nil {
			t.Fatalf("ice %d rejected: %v", i, err)
		}
	}
	if err := hub.Handle("alice", event(uuid(900), start.CallID, "rtc.ice", map[string]any{"candidate": "candidate"})); err == nil {
		t.Fatal("extra ICE event error = nil, want rate limit")
	}
	if err := hub.Handle("alice", event("018f7d51-3f90-7e63-b657-4a83a6a90499", start.CallID, "call.end", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	if _, err := hub.ActiveCall("alice"); err == nil {
		t.Fatal("ActiveCall after end error = nil, want released participant")
	}
	if got, ok := alice.TryNext(); ok {
		t.Fatalf("alice received own event: %+v", got)
	}
}

func TestHubExpiresRingingCall(t *testing.T) {
	now := time.Unix(2000, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	_ = hub.Connect("alice")
	bob := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90501", "018f7d51-40a1-7bb5-a2d0-7e47f9180501", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	now = now.Add((protocol.RingTimeoutSecs + 1) * time.Second)

	expired := hub.ExpireWaiting()
	if expired != 1 {
		t.Fatalf("ExpireWaiting() = %d, want 1", expired)
	}
	if _, err := hub.ActiveCall("alice"); err == nil {
		t.Fatal("ActiveCall after expiry error = nil")
	}
	if got := next(t, bob); got.Type != "call.expire" {
		t.Fatalf("bob expiry event = %+v", got)
	}
	if got := hub.ExpireWaiting(); got != 0 {
		t.Fatalf("second ExpireWaiting() = %d, want 0", got)
	}

	now = now.Add(TerminalRetention + time.Second)
	hub.Sweep()
	if _, err := hub.Resume("alice", start.CallID, 0); err == nil {
		t.Fatal("Resume after terminal retention error = nil")
	}
}

func TestHubEndsAbandonedActiveCallAndReleasesBothUsers(t *testing.T) {
	now := time.Unix(2_000, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")
	start := activeCall(t, hub, alice, bob, 1201)

	hub.Disconnect(bob)
	now = now.Add(30*time.Second + time.Millisecond)
	if got := hub.Sweep(); got != 1 {
		t.Fatalf("Sweep() = %d, want 1", got)
	}
	disconnectEventID := start.CallID[:len(start.CallID)-3] + "998"
	if got := next(t, alice); got.Type != "call.end" || got.ID != disconnectEventID || string(got.Payload) != `{"reason":"participant_disconnected"}` {
		t.Fatalf("call end = %+v", got)
	}
	replayed, err := hub.Resume("bob", start.CallID, 4)
	if err != nil {
		t.Fatal(err)
	}
	if len(replayed) != 1 || replayed[0].Type != "call.end" || string(replayed[0].Payload) != `{"reason":"participant_disconnected"}` {
		t.Fatalf("bob replay = %+v", replayed)
	}
	if _, err := hub.ActiveCall("alice"); err == nil {
		t.Fatal("alice still busy")
	}
	if _, err := hub.ActiveCall("bob"); err == nil {
		t.Fatal("bob still busy")
	}
	if err := hub.Handle("alice", event(uuid(1204), uuid(1205), "call.start", map[string]any{"callee_id": "carol"})); err != nil {
		t.Fatal(err)
	}
}

func TestHubKeepsActiveCallBeforeDisconnectGrace(t *testing.T) {
	now := time.Unix(2_000, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")
	start := activeCall(t, hub, alice, bob, 1211)

	hub.Disconnect(bob)
	now = now.Add(30*time.Second - time.Millisecond)
	if got := hub.Sweep(); got != 0 {
		t.Fatalf("Sweep() = %d, want 0", got)
	}
	if got, err := hub.ActiveCall("alice"); err != nil || got != start.CallID {
		t.Fatalf("ActiveCall(alice) = %q, %v; want %q, nil", got, err, start.CallID)
	}
}

func TestHubReconnectClearsActiveDisconnectGrace(t *testing.T) {
	now := time.Unix(2_000, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")
	start := activeCall(t, hub, alice, bob, 1221)

	hub.Disconnect(bob)
	if _, err := hub.ConnectChecked("bob"); err != nil {
		t.Fatal(err)
	}
	now = now.Add(30*time.Second + time.Millisecond)
	if got := hub.Sweep(); got != 0 {
		t.Fatalf("Sweep() = %d, want 0", got)
	}
	if got, err := hub.ActiveCall("alice"); err != nil || got != start.CallID {
		t.Fatalf("ActiveCall(alice) = %q, %v; want %q, nil", got, err, start.CallID)
	}
}

func TestHubWaitsForLastConnectionBeforeTrackingActiveDisconnect(t *testing.T) {
	now := time.Unix(2_000, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alice := hub.Connect("alice")
	bobPhone := hub.Connect("bob")
	bobTablet := hub.Connect("bob")
	start := event(uuid(1231), uuid(1232), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone)
	_ = next(t, bobTablet)
	if err := hub.Handle("bob", event(uuid(1233), start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alice)
	_ = next(t, alice)
	_ = next(t, bobPhone)
	_ = next(t, bobTablet)

	hub.Disconnect(bobPhone)
	now = now.Add(30*time.Second + time.Millisecond)
	if got := hub.Sweep(); got != 0 {
		t.Fatalf("Sweep() = %d, want 0", got)
	}
	if got, err := hub.ActiveCall("alice"); err != nil || got != start.CallID {
		t.Fatalf("ActiveCall(alice) = %q, %v; want %q, nil", got, err, start.CallID)
	}
}

func TestHubTracksParticipantAlreadyOfflineWhenCallBecomesActive(t *testing.T) {
	now := time.Unix(2_000, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")
	start := event(uuid(1241), uuid(1242), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)

	hub.Disconnect(bob)
	if err := hub.Handle("bob", event(uuid(1243), start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alice)
	_ = next(t, alice)
	now = now.Add(30*time.Second + time.Millisecond)
	if got := hub.Sweep(); got != 1 {
		t.Fatalf("Sweep() = %d, want 1", got)
	}
	if got := next(t, alice); got.Type != "call.end" {
		t.Fatalf("event = %+v", got)
	}
	if _, err := hub.ActiveCall("alice"); err == nil {
		t.Fatal("alice still busy")
	}
}

func TestHubLimitsConnectionsPerUser(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	for i := 0; i < MaxConnectionsPerUser; i++ {
		if _, err := hub.ConnectChecked("alice"); err != nil {
			t.Fatalf("ConnectChecked %d error = %v", i, err)
		}
	}
	if _, err := hub.ConnectChecked("alice"); err == nil {
		t.Fatal("extra ConnectChecked error = nil, want limit")
	}
}

func TestHubDisconnectReleasesConnectionSlot(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	client, err := hub.ConnectChecked("alice")
	if err != nil {
		t.Fatal(err)
	}
	hub.Disconnect(client)

	for i := 0; i < MaxConnectionsPerUser; i++ {
		if _, err := hub.ConnectChecked("alice"); err != nil {
			t.Fatalf("ConnectChecked %d error = %v", i, err)
		}
	}
}

func TestHubDeliversToEveryConnectionForUser(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	bobPhone := hub.Connect("bob")
	bobTablet := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90701", "018f7d51-40a1-7bb5-a2d0-7e47f9180701", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobPhone); got.Type != "call.incoming" {
		t.Fatalf("phone event = %+v", got)
	}
	if got := next(t, bobTablet); got.Type != "call.incoming" {
		t.Fatalf("tablet event = %+v", got)
	}
}

func TestHubSupportsThreeConcurrentCallsAcrossSixUsers(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	clients := map[string]*Client{}
	for _, login := range []string{"alice", "bob", "carol", "dave", "erin", "frank"} {
		clients[login] = hub.Connect(login)
	}
	pairs := [][2]string{{"alice", "bob"}, {"carol", "dave"}, {"erin", "frank"}}
	for i, pair := range pairs {
		callID := uuid(200 + i)
		if err := hub.Handle(pair[0], event(uuid(100+i), callID, "call.start", map[string]any{"callee_id": pair[1]})); err != nil {
			t.Fatal(err)
		}
		if incoming := next(t, clients[pair[1]]); incoming.CallID != callID || incoming.Type != "call.incoming" {
			t.Fatalf("incoming = %+v", incoming)
		}
	}
}

func TestHubReplayContainsOnlyEventsAddressedToUser(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	hub.SetICEConfigProvider(fakeICEConfig{})
	alice := hub.Connect("alice")
	bob := hub.Connect("bob")

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90801", "018f7d51-40a1-7bb5-a2d0-7e47f9180801", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	if err := hub.Handle("bob", event("018f7d51-3f90-7e63-b657-4a83a6a90802", start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alice) // call.accept, seq 2
	_ = next(t, alice) // alice rtc.config, seq 3
	_ = next(t, bob)   // bob rtc.config, seq 4

	offer := event("018f7d51-3f90-7e63-b657-4a83a6a90803", start.CallID, "rtc.offer", map[string]any{"sdp": "offer"})
	if err := hub.Handle("alice", offer); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)

	aliceReplay, err := hub.Resume("alice", start.CallID, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(aliceReplay) != 0 {
		t.Fatalf("alice replay leaked events addressed to bob: %+v", aliceReplay)
	}
	bobReplay, err := hub.Resume("bob", start.CallID, 4)
	if err != nil {
		t.Fatal(err)
	}
	if len(bobReplay) != 1 || bobReplay[0].Type != "rtc.offer" {
		t.Fatalf("bob replay = %+v", bobReplay)
	}
}

func TestHubRejectsSelfCallAndInvalidTransitions(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	selfCall := event("018f7d51-3f90-7e63-b657-4a83a6a90901", "018f7d51-40a1-7bb5-a2d0-7e47f9180901", "call.start", map[string]any{"callee_id": "alice"})
	if err := hub.Handle("alice", selfCall); err == nil {
		t.Fatal("self call error = nil")
	}

	start := event("018f7d51-3f90-7e63-b657-4a83a6a90902", "018f7d51-40a1-7bb5-a2d0-7e47f9180902", "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	if err := hub.Handle("alice", event("018f7d51-3f90-7e63-b657-4a83a6a90903", start.CallID, "call.accept", map[string]any{})); err == nil {
		t.Fatal("caller accepted own call")
	}
	if err := hub.Handle("alice", event("018f7d51-3f90-7e63-b657-4a83a6a90904", start.CallID, "rtc.ice", map[string]any{"candidate": "candidate"})); err == nil {
		t.Fatal("ICE before acceptance error = nil")
	}
}

func TestHubDoesNotWaitForIncomingCallNotification(t *testing.T) {
	notifier := &blockingNotifier{started: make(chan struct{}), release: make(chan struct{})}
	hub := NewHub(notifier)
	done := make(chan error, 1)
	start := event("018f7d51-3f90-7e63-b657-4a83a6a91001", "018f7d51-40a1-7bb5-a2d0-7e47f9181001", "call.start", map[string]any{"callee_id": "bob"})
	go func() { done <- hub.Handle("alice", start) }()

	select {
	case <-notifier.started:
	case <-time.After(time.Second):
		t.Fatal("notifier was not called")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(100 * time.Millisecond):
		close(notifier.release)
		t.Fatal("Handle waited for notifier")
	}
	close(notifier.release)
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

func activeCall(t *testing.T, hub *Hub, alice, bob *Client, id int) protocol.Event {
	t.Helper()
	start := event(uuid(id), uuid(id+1), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	if err := hub.Handle("bob", event(uuid(id+2), start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alice)
	_ = next(t, alice)
	_ = next(t, bob)
	return start
}

func uuid(n int) string {
	return fmt.Sprintf("018f7d51-3f90-7e63-b657-4a83a6a%05d", n)
}

type fakeICEConfig struct{}

func (fakeICEConfig) ICEConfig(callID, user string) json.RawMessage {
	return json.RawMessage(fmt.Sprintf(`{"user":%q,"call_id":%q}`, user, callID))
}

type blockingNotifier struct {
	started chan struct{}
	release chan struct{}
}

func (n *blockingNotifier) IncomingCall(string, string, DeliveredEvent) {
	close(n.started)
	<-n.release
}

func (n *blockingNotifier) CancelCall(string, DeliveredEvent) {}
