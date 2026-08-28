package httpapi

import (
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"tinitalk/internal/signaling"
)

func TestSocketUsesConnectedDeviceForMediaBinding(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	server := httptest.NewServer(NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}))
	defer server.Close()

	alicePhone := dialDeviceSocket(t, server.URL, "alice", tokens["alice"], "phone")
	defer alicePhone.Close()
	aliceTablet := dialDeviceSocket(t, server.URL, "alice", tokens["alice"], "tablet")
	defer aliceTablet.Close()
	bobPhone := dialDeviceSocket(t, server.URL, "bob", tokens["bob"], "phone")
	defer bobPhone.Close()
	bobTablet := dialDeviceSocket(t, server.URL, "bob", tokens["bob"], "tablet")
	defer bobTablet.Close()

	callID := "018f7d51-40a1-7bb5-a2d0-7e47f9182482"
	writeSocketEvent(t, alicePhone, "018f7d51-3f90-7e63-b657-4a83a6a02481", callID, "call.start", map[string]any{"callee_id": "bob"})
	readSocketEvent(t, bobPhone)
	readSocketEvent(t, bobTablet)
	writeSocketEvent(t, bobTablet, "018f7d51-3f90-7e63-b657-4a83a6a02483", callID, "call.accept", map[string]any{})
	if got := readSocketEvent(t, alicePhone); got["type"] != "call.accept" {
		t.Fatalf("alice phone accept = %+v", got)
	}
	readSocketEvent(t, alicePhone) // rtc.config
	readSocketEvent(t, bobTablet)  // rtc.config

	offerID := "018f7d51-3f90-7e63-b657-4a83a6a02484"
	writeSocketEvent(t, alicePhone, offerID, callID, "rtc.offer", map[string]any{"sdp": "offer"})
	if got := readSocketEvent(t, bobTablet); got["id"] != offerID {
		t.Fatalf("bob tablet offer = %+v", got)
	}
	assertSocketHasNoEvent(t, aliceTablet)
	assertSocketHasNoEvent(t, bobPhone)
}

func assertSocketHasNoEvent(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	if err := conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := conn.ReadMessage(); err == nil {
		t.Fatal("unexpected socket event")
	}
}
