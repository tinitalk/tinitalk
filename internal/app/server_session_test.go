package app

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"tinitalk/internal/firebaseconfig"
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

func TestNewHTTPServerWiresFirebaseConfiguration(t *testing.T) {
	db, err := state.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(nil, nil); err != nil {
		t.Fatal(err)
	}
	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	server := NewHTTPServer(db, ServerConfig{
		AllowInsecureLoopback: true,
		FirebaseConfig: firebaseconfig.Config{
			MobileSDKAppID: "app-id",
			CurrentKey:     "api-key",
			ProjectID:      "project-id",
			ProjectNumber:  "123",
			ConfigID:       "config-id",
		},
	})
	req := httptest.NewRequest(http.MethodGet, "/api/firebase-config", nil)
	req.SetBasicAuth("alice", token)
	response := httptest.NewRecorder()
	server.Handler.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("Firebase config status = %d, body %s", response.Code, response.Body.String())
	}
	var got map[string]string
	if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["config_id"] != "config-id" {
		t.Fatalf("Firebase config = %#v, want startup configuration", got)
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
