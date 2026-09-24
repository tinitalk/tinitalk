package signaling

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/state"
)

func TestWaitingLimitAndSessionReplacement(t *testing.T) {
	h, b, first, _ := waitingFixture(t)
	for i := 0; i < MaxWaitingCalls; i++ {
		addWaiting(t, h, b, fmt.Sprintf("caller%d", i), 6000+i*10)
	}
	if err := h.Handle("excess", event(uuid(6200), uuid(6201), "call.start", map[string]any{"callee_id": "bob"})); err != ErrCalleeBusy {
		t.Fatalf("limit: %v", err)
	}
	h.ReplaceSession("bob", "replacement")
	if len(h.incomingByUser["bob"]) != 0 || h.calls[first.CallID].state != callEnded {
		t.Fatal("session replacement left calls alive")
	}
	for i := 0; i < MaxWaitingCalls; i++ {
		if h.primaryCallID(fmt.Sprintf("caller%d", i)) != "" {
			t.Fatal("caller stayed busy")
		}
	}
}

func TestExpiredSelectionDoesNotEndCurrentEvenBeforeSweep(t *testing.T) {
	h, b, first, now := waitingFixture(t)
	e := addWaiting(t, h, b, "carol", 6300)
	*now = now.Add(WaitingTimeout)
	if err := h.HandleClient(b, event(uuid(6303), e.CallID, "call.accept", map[string]any{"replace_call_id": first.CallID})); err == nil {
		t.Fatal("accepted expired call")
	}
	if h.calls[first.CallID].state != callActive {
		t.Fatal("expired selection ended conversation")
	}
}

func TestWaitingSnapshotDoesNotLeakToOtherSession(t *testing.T) {
	h, b, _, _ := waitingFixture(t)
	e := addWaiting(t, h, b, "carol", 6400)
	c := h.calls[e.CallID]
	c.incomingTargetKnown, c.incomingTargetID, c.incomingTargetDevice = true, "session", "phone"
	snapshot, err := h.ActiveCallSnapshotForDevice("bob", "other", "other")
	if err != nil || len(snapshot.IncomingCalls) != 0 {
		t.Fatalf("leaked waiting calls: %+v %v", snapshot, err)
	}
	if err := h.HandleClient(b, event(uuid(6403), e.CallID, "call.waiting", map[string]any{"waiting": false})); err == nil {
		t.Fatal("wrong device changed waiting")
	}
}

func waitingFixture(t *testing.T) (*Hub, *Client, protocol.Event, *time.Time) {
	t.Helper()
	h := NewHub(nil)
	now := time.Unix(1000, 0)
	h.now = func() time.Time { return now }
	a, b := h.Connect("alice"), h.Connect("bob")
	h.EnableCallWaiting(b)
	first := activeCall(t, h, a, b, 5000)
	if err := h.HandleClient(b, event(uuid(5100), first.CallID, "call.connected", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	return h, b, first, &now
}

func addWaiting(t *testing.T, h *Hub, b *Client, caller string, id int) protocol.Event {
	t.Helper()
	e := event(uuid(id), uuid(id+1), "call.start", map[string]any{"callee_id": "bob"})
	if err := h.Handle(caller, e); err != nil {
		t.Fatal(err)
	}
	if err := h.HandleClient(b, event(uuid(id+2), e.CallID, "call.waiting", map[string]any{"waiting": true})); err != nil {
		t.Fatal(err)
	}
	return e
}

func TestWaitingTimeoutDoesNotInterruptActiveConversation(t *testing.T) {
	h, b, first, now := waitingFixture(t)
	e := addWaiting(t, h, b, "carol", 5200)
	*now = now.Add(14 * time.Second)
	if n := h.Sweep(); n != 0 {
		t.Fatalf("expired early: %d", n)
	}
	*now = now.Add(time.Second)
	if n := h.Sweep(); n != 1 {
		t.Fatalf("expired = %d", n)
	}
	if h.activeByUser["bob"] != first.CallID || h.calls[e.CallID].state != callEnded {
		t.Fatal("wrong call ended")
	}
	replay := h.calls[e.CallID].after("carol", 0)
	last := replay[len(replay)-1]
	if last.Type != "call.reject" || string(last.Payload) != `{"reason":"busy"}` {
		t.Fatalf("terminal: %+v", last)
	}
}

func TestWaitingDeadlinesAreIndependentAndCannotBeExtended(t *testing.T) {
	h, b, _, now := waitingFixture(t)
	first := addWaiting(t, h, b, "carol", 5300)
	*now = now.Add(8 * time.Second)
	second := addWaiting(t, h, b, "dave", 5310)
	if err := h.HandleClient(b, event(uuid(5330), first.CallID, "call.waiting", map[string]any{"waiting": true})); err != nil {
		t.Fatal(err)
	}
	*now = now.Add(7 * time.Second)
	h.Sweep()
	if h.calls[first.CallID].state != callEnded || h.calls[second.CallID].state != callRinging {
		t.Fatal("deadlines were reset or shared")
	}
	snapshot, err := h.ActiveCallSnapshotForDevice("bob", "", "")
	if err != nil || len(snapshot.IncomingCalls) != 1 || snapshot.IncomingCalls[0].CallID != second.CallID {
		t.Fatalf("snapshot: %+v %v", snapshot, err)
	}
}

func TestWaitingSwitchChecksSelectedCallBeforeEndingCurrent(t *testing.T) {
	for _, cancel := range []bool{false, true} {
		h, b, first, _ := waitingFixture(t)
		e := addWaiting(t, h, b, "carol", 5400)
		if cancel {
			if err := h.Handle("carol", event(uuid(5403), e.CallID, "call.cancel", map[string]any{})); err != nil {
				t.Fatal(err)
			}
		}
		err := h.HandleClient(b, event(uuid(5404), e.CallID, "call.accept", map[string]any{"replace_call_id": first.CallID}))
		if cancel {
			if err == nil || h.calls[first.CallID].state != callActive {
				t.Fatal("cancelled incoming interrupted conversation")
			}
		} else if err != nil || h.calls[first.CallID].state != callEnded || h.activeByUser["bob"] != e.CallID {
			t.Fatalf("switch failed: %v", err)
		}
	}
}

func TestWaitingRequiresReceiverSupportAndEstablishedConversation(t *testing.T) {
	h, b, _, _ := waitingFixture(t)
	b.callWaiting = false
	if err := h.Handle("carol", event(uuid(5500), uuid(5501), "call.start", map[string]any{"callee_id": "bob"})); err != ErrCalleeBusy {
		t.Fatalf("legacy receiver: %v", err)
	}
	b.callWaiting = true
	h.calls[h.activeByUser["bob"]].connectedAt = time.Time{}
	if err := h.Handle("carol", event(uuid(5502), uuid(5503), "call.start", map[string]any{"callee_id": "bob"})); err != ErrCalleeBusy {
		t.Fatalf("unconnected receiver: %v", err)
	}
}

func TestCrossServerWaitingDoesNotRequireLocalActiveCall(t *testing.T) {
	h := NewHub(nil)
	b := h.Connect("bob")
	h.EnableCallWaiting(b)
	e := addWaiting(t, h, b, "alice", 5600)
	if !h.calls[e.CallID].waiting {
		t.Fatal("cross-server waiting was not marked")
	}
	if err := h.HandleClient(b, event(uuid(5604), e.CallID, "call.waiting", map[string]any{"waiting": false})); err != nil {
		t.Fatal(err)
	}
	if h.calls[e.CallID].waiting {
		t.Fatal("ordinary ringing was not restored")
	}
}

func TestSeveralInvitationsWhileConversationIsOnAnotherServer(t *testing.T) {
	h := NewHub(nil)
	b := h.Connect("bob")
	h.EnableCallWaiting(b)
	first := addWaiting(t, h, b, "alice", 6600)
	second := addWaiting(t, h, b, "carol", 6610)
	if len(h.incomingByUser["bob"]) != 2 {
		t.Fatal("another server's active conversation limited invitations to one")
	}
	if err := h.Handle("alice", event(uuid(6620), first.CallID, "call.cancel", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	if h.calls[second.CallID].state != callRinging {
		t.Fatal("cancellation ended the other invitation")
	}
}

func TestBusyRejectionPersistsMissedBusyOutcome(t *testing.T) {
	h, db, _ := historyHub(t)
	b := h.Connect("bob")
	h.EnableCallWaiting(b)
	e := addWaiting(t, h, b, "alice", 5700)
	if err := h.HandleClient(b, event(uuid(5703), e.CallID, "call.reject", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil || len(page.Items) != 1 || page.Items[0].Outcome != state.CallOutcomeBusy || page.UnreadMissed != 1 {
		t.Fatalf("history: %+v %v", page, err)
	}
}

func TestManualWaitingRejectionPreservesHistoryWithoutNewUnread(t *testing.T) {
	h, db, now := historyHub(t)
	if err := db.RecordBusyCall(uuid(7900), "alice", "bob", now.Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	b := h.Connect("bob")
	h.EnableCallWaiting(b)
	e := addWaiting(t, h, b, "alice", 7910)
	rejection := event(uuid(7913), e.CallID, "call.reject", map[string]any{"reason": "busy", "seen": true})
	if err := h.HandleClient(b, rejection); err != nil {
		t.Fatal(err)
	}
	// Retrying the same action must not insert unread history or clear older calls.
	if err := h.HandleClient(b, rejection); err != nil {
		t.Fatal(err)
	}
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil || len(page.Items) != 2 || page.UnreadMissed != 1 {
		t.Fatalf("history: %+v %v", page, err)
	}
	for _, item := range page.Items {
		if item.Outcome != state.CallOutcomeBusy {
			t.Fatalf("outcome: %+v", item)
		}
	}
	caller, err := db.CallHistory("alice", 0, 10)
	if err != nil || len(caller.Items) != 2 || caller.Items[0].Outcome != state.CallOutcomeBusy {
		t.Fatalf("caller: %+v %v", caller, err)
	}
}

func TestWaitingTimeoutStillCreatesUnreadMissedCall(t *testing.T) {
	h, db, now := historyHub(t)
	b := h.Connect("bob")
	h.EnableCallWaiting(b)
	e := addWaiting(t, h, b, "alice", 7920)
	*now = now.Add(16 * time.Second)
	h.Sweep()
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil || len(page.Items) != 1 || page.Items[0].CallID != e.CallID || page.Items[0].Outcome != state.CallOutcomeBusy || page.UnreadMissed != 1 {
		t.Fatalf("history: %+v %v", page, err)
	}
}

func TestSeenWaitingRejectionKeepsLegacyBusySignal(t *testing.T) {
	h, db, _ := historyHub(t)
	b := h.Connect("bob")
	h.EnableCallWaiting(b)
	e := addWaiting(t, h, b, "alice", 7930)
	if err := h.HandleClient(b, event(uuid(7933), e.CallID, "call.reject", map[string]any{"reason": "busy", "seen": true})); err != nil {
		t.Fatal(err)
	}
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil || len(page.Items) != 1 || page.UnreadMissed != 0 {
		t.Fatalf("history: %+v %v", page, err)
	}
	replay := h.calls[e.CallID].after("alice", 0)
	last := replay[len(replay)-1]
	if last.Type != "call.reject" || string(last.Payload) != `{"reason":"busy"}` {
		t.Fatalf("legacy signal: %+v", last)
	}
}

func TestCallerCannotMarkWaitingCallSeenForRecipient(t *testing.T) {
	h, db, _ := historyHub(t)
	b := h.Connect("bob")
	h.EnableCallWaiting(b)
	e := addWaiting(t, h, b, "alice", 7940)
	if err := h.Handle("alice", event(uuid(7943), e.CallID, "call.reject", map[string]any{"reason": "busy", "seen": true})); err == nil {
		t.Fatal("caller rejected recipient's invitation")
	}
	if err := h.Handle("alice", event(uuid(7944), e.CallID, "call.cancel", map[string]any{"seen": true})); err != nil {
		t.Fatal(err)
	}
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil || len(page.Items) != 1 || page.UnreadMissed != 1 {
		t.Fatalf("history: %+v %v", page, err)
	}
}

func TestWaitingAcknowledgementCannotBeSentByCaller(t *testing.T) {
	h, b, _, _ := waitingFixture(t)
	e := addWaiting(t, h, b, "carol", 5800)
	caller := h.Connect("carol")
	h.EnableCallWaiting(caller)
	payload := json.RawMessage(`{"waiting":false}`)
	if err := h.HandleClient(caller, protocol.Event{ID: uuid(5803), CallID: e.CallID, Type: "call.waiting", Payload: payload}); err == nil {
		t.Fatal("caller altered waiting state")
	}
}
