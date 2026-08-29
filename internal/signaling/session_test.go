package signaling

import (
	"encoding/json"
	"errors"
	"testing"
	"time"

	"tinitalk/internal/state"
)

func TestHubReplacementEvictsOldSessionAndRejectsItsReconnect(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	legacy := connectDevice(t, hub, "alice", "old-phone")

	hub.ReplaceSession("alice", "session-1")
	if hub.Connected(legacy) {
		t.Fatal("legacy client remained connected after first managed claim")
	}
	current, err := hub.ConnectSessionChecked("alice", "phone", "session-1")
	if err != nil || !hub.Connected(current) {
		t.Fatalf("current session connect = %v, connected %v", err, hub.Connected(current))
	}

	hub.ReplaceSession("alice", "session-2")
	if hub.Connected(current) {
		t.Fatal("previous managed session remained connected after replacement")
	}
	if _, err := hub.ConnectSessionChecked("alice", "phone", "session-1"); !errors.Is(err, state.ErrSessionReplaced) {
		t.Fatalf("stale reconnect error = %v, want ErrSessionReplaced", err)
	}
	replacement, err := hub.ConnectSessionChecked("alice", "tablet", "session-2")
	if err != nil || !hub.Connected(replacement) {
		t.Fatalf("replacement connect = %v, connected %v", err, hub.Connected(replacement))
	}
}

func TestHubReplacementImmediatelyTerminatesActiveCallAndHistory(t *testing.T) {
	hub, db, _ := historyHub(t)
	alice, err := hub.ConnectSessionChecked("alice", "phone", "session-1")
	if err != nil || !hub.Connected(alice) {
		t.Fatalf("alice connect = %v", err)
	}
	bob := connectDevice(t, hub, "bob", "phone")
	start := event(uuid(2701), uuid(2702), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.HandleClient(alice, start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob) // call.incoming
	if err := hub.HandleClient(bob, event(uuid(2703), start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alice) // call.accept
	_ = next(t, alice) // rtc.config
	_ = next(t, bob)   // rtc.config
	if err := hub.HandleClient(alice, event(uuid(2704), start.CallID, "call.connected", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob) // call.connected

	hub.ReplaceSession("alice", "session-2")

	if hub.Connected(alice) {
		t.Fatal("replaced call participant remained connected")
	}
	terminal := next(t, bob)
	if terminal.Type != "call.end" || terminal.CallID != start.CallID {
		t.Fatalf("peer terminal event = %+v, want call.end", terminal)
	}
	var payload map[string]string
	if err := json.Unmarshal(terminal.Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["reason"] != "session_replaced" {
		t.Fatalf("terminal reason = %q, want session_replaced", payload["reason"])
	}
	if _, err := hub.ActiveCall("bob"); err == nil {
		t.Fatal("peer remained busy after session replacement")
	}
	page, err := db.CallHistory("alice", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].Outcome != state.CallOutcomeInterrupted {
		t.Fatalf("replacement call history = %+v, want interrupted", page.Items)
	}
}

func TestHubSnapshotsManagedSessionForQueuedCallNotification(t *testing.T) {
	notifier := &targetCaptureNotifier{incoming: make(chan DeliveredEvent, 1)}
	hub := NewHub(notifier)
	hub.SetSessionStore(staticSessionStore{login: "bob", sessionID: "bob-session"})
	start := event(uuid(2711), uuid(2712), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	select {
	case delivered := <-notifier.incoming:
		if !delivered.TargetSessionKnown || delivered.TargetSessionID != "bob-session" || delivered.TargetDeviceID != "phone" {
			t.Fatalf("notification target = known %v session %q device %q, want bob-session/phone snapshot", delivered.TargetSessionKnown, delivered.TargetSessionID, delivered.TargetDeviceID)
		}
	case <-time.After(time.Second):
		t.Fatal("incoming call notification was not sent")
	}
}

func TestHubMarksCallNotificationWhenSessionSnapshotFails(t *testing.T) {
	notifier := &targetCaptureNotifier{incoming: make(chan DeliveredEvent, 1)}
	hub := NewHub(notifier)
	hub.SetSessionStore(failingSessionStore{})
	start := event(uuid(2721), uuid(2722), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.Handle("alice", start); err != nil {
		t.Fatal(err)
	}
	select {
	case delivered := <-notifier.incoming:
		if !delivered.TargetResolutionFailed {
			t.Fatalf("target resolution failed = %v, want true", delivered.TargetResolutionFailed)
		}
	case <-time.After(time.Second):
		t.Fatal("incoming call notification was not sent to notifier")
	}
}

type targetCaptureNotifier struct {
	incoming chan DeliveredEvent
}

func (n *targetCaptureNotifier) IncomingCall(_, _ string, event DeliveredEvent) {
	n.incoming <- event
}

func (*targetCaptureNotifier) CancelCall(string, DeliveredEvent) {}

type staticSessionStore struct {
	login     string
	sessionID string
}

func (s staticSessionStore) CurrentSession(login string) (state.AccountSession, bool, error) {
	if login != s.login {
		return state.AccountSession{}, false, nil
	}
	return state.AccountSession{DeviceID: "phone", SessionID: s.sessionID}, true, nil
}

type failingSessionStore struct{}

func (failingSessionStore) CurrentSession(string) (state.AccountSession, bool, error) {
	return state.AccountSession{}, false, errors.New("session lookup failed")
}
