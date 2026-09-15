package httpapi

import (
	"net/http"
	"testing"
	"tinitalk/internal/signaling"
)

func TestContactChangesReachWebWithoutPushNotifier(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(nil)
	server := NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub})
	web := hub.Connect("bob")
	hub.EnableContactChanges(web)
	for _, method := range []string{http.MethodDelete, http.MethodPut} {
		response := request(t, server, method, "/api/contacts/bob", []byte(`{"custom_name":"Bob"}`), "alice", tokens["alice"])
		if response.Code >= 300 {
			t.Fatal(response.Body.String())
		}
		event, ok := web.TryNext()
		if !ok || event.Type != "contact.changed" {
			t.Fatalf("missing event after %s", method)
		}
	}
	if err := db.RemoveContact("bob", "alice"); err != nil {
		t.Fatal(err)
	}
	request(t, server, http.MethodDelete, "/api/contacts/bob", nil, "alice", tokens["alice"])
	if _, ok := web.TryNext(); ok {
		t.Fatal("event leaked a contact outside the personal address book")
	}
}
