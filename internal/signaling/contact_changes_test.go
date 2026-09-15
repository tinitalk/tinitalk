package signaling

import "testing"

func TestContactChangesOnlyReachOptedInCurrentSession(t *testing.T) {
	h := NewHub(nil)
	web, _ := h.ConnectSessionChecked("alice", "web", "current")
	native, _ := h.ConnectSessionChecked("alice", "native", "current")
	h.EnableContactChanges(web)
	h.ContactChanged("alice", "bob", "old")
	if _, ok := web.TryNext(); ok {
		t.Fatal("stale session delivered")
	}
	h.ContactChanged("alice", "bob", "current")
	event, ok := web.TryNext()
	if !ok || event.Type != "contact.changed" || string(event.Payload) != `{"contact_login":"bob"}` {
		t.Fatalf("event: %+v", event)
	}
	if _, ok := native.TryNext(); ok {
		t.Fatal("native received web event")
	}
}
