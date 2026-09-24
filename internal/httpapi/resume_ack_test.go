package httpapi

import (
	"net/http/httptest"
	"testing"

	"tinitalk/internal/signaling"
)

func TestResumeAcknowledgementFollowsReplay(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	server := httptest.NewServer(NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}))
	defer server.Close()
	alice := dialSocket(t, server.URL, "alice", tokens["alice"])
	defer alice.Close()
	bob, _ := dialAcknowledgedSocket(t, server.URL, "bob", tokens["bob"])
	defer bob.Close()
	callID := "018f7d51-40a1-7bb5-a2d0-7e47f9180391"
	writeSocketEvent(t, alice, "018f7d51-3f90-7e63-b657-4a83a6a90391", callID, "call.start", map[string]any{"callee_id": "bob"})
	if incoming := readSocketEvent(t, bob); incoming["type"] != "call.incoming" {
		t.Fatalf("incoming = %+v", incoming)
	}
	resumeID := "018f7d51-3f90-7e63-b657-4a83a6a90392"
	writeSocketEvent(t, bob, resumeID, callID, "call.resume", map[string]any{"last_seq": 0})
	if replay := readSocketEvent(t, bob); replay["type"] != "call.incoming" {
		t.Fatalf("replay must precede acknowledgement: %+v", replay)
	}
	if ack := readSocketEvent(t, bob); ack["ack"] != resumeID {
		t.Fatalf("resume acknowledgement = %+v", ack)
	}
}
