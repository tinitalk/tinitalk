package app

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"tinitalk/internal/state"
)

const testWebPushSubscription = `{"endpoint":"https://fcm.distributor.unifiedpush.org/wpfcm?t=app-test","keys":{"p256dh":"BEkDdNnpEcD8M4mRGOFJWTDJ4GkDI5Xs3vpIOrAaBZKRCVv6V3sB3CFujTFiD6DHda7W8pCyChJDU205otrbCAw","auth":"AAAAAAAAAAAAAAAAAAAAAA"}}`

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
	target := state.PushTarget{Subscription: testWebPushSubscription, ConfigID: "sha256:webpush"}
	if err := db.UpsertPushTarget("alice", "old-phone", target); err != nil {
		t.Fatal(err)
	}
	notifier := &captureSessionNotifier{calls: make(chan capturedSessionReplacement, 1)}
	server := NewHTTPServer(db, ServerConfig{AllowInsecureLoopback: true, WebPushConfigID: "sha256:webpush", SessionNotifier: notifier})
	req := httptest.NewRequest(http.MethodPost, "/api/session", bytes.NewBufferString(`{"device_id":"tablet","webpush_subscription":`+testWebPushSubscription+`,"config_id":"sha256:webpush"}`))
	req.SetBasicAuth("alice", token)
	recorder := httptest.NewRecorder()
	server.Handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("claim status = %d, body %s", recorder.Code, recorder.Body.String())
	}
	select {
	case got := <-notifier.calls:
		if got.login != "alice" || got.revokedSessionID != "" || len(got.devices) != 1 || got.devices[0].PushTarget != target {
			t.Fatalf("captured replacement = %+v, want WebPush target", got)
		}
	case <-time.After(time.Second):
		t.Fatal("session replacement notifier was not called")
	}
}

func TestNewHTTPServerWiresWebPushConfiguration(t *testing.T) {
	db, err := state.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(); err != nil {
		t.Fatal(err)
	}
	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	server := NewHTTPServer(db, ServerConfig{
		AllowInsecureLoopback: true,
		WebPushPublicKey:      "vapid-public-key",
		WebPushConfigID:       "sha256:webpush",
	})
	req := httptest.NewRequest(http.MethodGet, "/api/webpush-config", nil)
	req.SetBasicAuth("alice", token)
	response := httptest.NewRecorder()
	server.Handler.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("WebPush config status = %d, body %s", response.Code, response.Body.String())
	}
	var got map[string]string
	if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["vapid_public_key"] != "vapid-public-key" || got["config_id"] != "sha256:webpush" {
		t.Fatalf("WebPush config = %#v, want startup configuration", got)
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
