package signaling

import (
	"testing"
	"time"
)

func TestIncomingCompletionDoesNotReleaseAnotherCall(t *testing.T) {
	h := NewHub(nil)
	active := &call{id: uuid(9901), caller: "alice", callee: "bob", state: callActive}
	incoming := &call{id: uuid(9902), caller: "carol", callee: "bob", state: callRinging}
	h.calls[active.id], h.calls[incoming.id] = active, incoming
	h.activateCall(active)
	h.activeByUser["carol"] = incoming.id
	h.addIncoming(incoming)
	h.end(incoming)
	if h.activeByUser["bob"] != active.id || h.activeByUser["alice"] != active.id {
		t.Fatal("ending an invitation released the active conversation")
	}
	if h.primaryCallID("carol") != "" || len(h.incomingByUser["bob"]) != 0 {
		t.Fatal("ended invitation remains registered")
	}
}

func TestRegistryPrefersActiveThenOldestIncoming(t *testing.T) {
	h := NewHub(nil)
	old := &call{id: uuid(9911), caller: "alice", callee: "bob", state: callRinging, startedAt: time.Unix(1, 0)}
	newer := &call{id: uuid(9912), caller: "carol", callee: "bob", state: callRinging, startedAt: time.Unix(2, 0)}
	h.addIncoming(newer)
	h.addIncoming(old)
	if h.primaryCallID("bob") != old.id {
		t.Fatal("oldest invitation was not selected")
	}
	h.activateCall(newer)
	if h.primaryCallID("bob") != newer.id {
		t.Fatal("active call was not selected")
	}
	h.end(newer)
	if h.primaryCallID("bob") != old.id {
		t.Fatal("other invitation was lost")
	}
}
