package app

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"tinitalk/internal/state"
)

func TestNewHTTPServerWiresSessionReplacementNotifier(t *testing.T) {
	db, err := state.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertDevice("alice", "old-phone", "old-fcm"); err != nil {
		t.Fatal(err)
	}
	notifier := &captureSessionNotifier{calls: make(chan capturedSessionReplacement, 1)}
	server := NewHTTPServer(db, ServerConfig{AllowInsecureLoopback: true, SessionNotifier: notifier})
	req := httptest.NewRequest(http.MethodPost, "/api/session", bytes.NewBufferString(`{"device_id":"tablet"}`))
	req.SetBasicAuth("alice", token)
	recorder := httptest.NewRecorder()
	server.Handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("claim status = %d, body %s", recorder.Code, recorder.Body.String())
	}
	select {
	case got := <-notifier.calls:
		if got.login != "alice" || got.revokedSessionID != "" || len(got.devices) != 1 || got.devices[0].DeviceID != "old-phone" {
			t.Fatalf("captured replacement = %+v, want legacy old-phone", got)
		}
	case <-time.After(time.Second):
		t.Fatal("session replacement notifier was not called")
	}
}

type capturedSessionReplacement struct {
	login            string
	revokedSessionID string
	devices          []state.Device
}

type captureSessionNotifier struct {
	calls chan capturedSessionReplacement
}

func (n *captureSessionNotifier) SessionReplaced(login, revokedSessionID string, devices []state.Device) {
	n.calls <- capturedSessionReplacement{login: login, revokedSessionID: revokedSessionID, devices: devices}
}
