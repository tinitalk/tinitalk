package httpapi

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

const (
	testSessionHeader    = "X-TiniTalk-Session-ID"
	testAuthReasonHeader = "X-TiniTalk-Auth-Reason"
	testReplacedReason   = "session_replaced"
)

func TestSessionClaimTransitionsLegacyAccountAndReplacesPriorClaim(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true})

	if got := request(t, server, http.MethodGet, "/api/me", nil, "alice", tokens["alice"]); got.Code != http.StatusOK {
		t.Fatalf("legacy /api/me status = %d, want 200", got.Code)
	}
	if got := requestWithSession(t, server, http.MethodPost, "/api/session", []byte(`{"device_id":""}`), "alice", tokens["alice"], ""); got.Code != http.StatusBadRequest {
		t.Fatalf("empty claim status = %d, want 400", got.Code)
	}

	first := claimHTTPSession(t, server, "alice", tokens["alice"], "phone")
	for name, sessionID := range map[string]string{"missing": "", "stale": "stale-session"} {
		got := requestWithSession(t, server, http.MethodGet, "/api/me", nil, "alice", tokens["alice"], sessionID)
		assertSessionReplacedResponse(t, name, got)
	}
	if got := requestWithSession(t, server, http.MethodGet, "/api/me", nil, "alice", tokens["alice"], first); got.Code != http.StatusOK {
		t.Fatalf("current session /api/me status = %d, want 200", got.Code)
	}
	retry := claimHTTPSession(t, server, "alice", tokens["alice"], "phone")
	if retry != first {
		t.Fatalf("same-device retry session = %q, want existing %q", retry, first)
	}

	second := claimHTTPSession(t, server, "alice", tokens["alice"], "tablet")
	if second == first {
		t.Fatal("different-device claim reused previous session ID")
	}
	assertSessionReplacedResponse(t, "first claim", requestWithSession(t, server, http.MethodGet, "/api/me", nil, "alice", tokens["alice"], first))
	if got := requestWithSession(t, server, http.MethodGet, "/api/me", nil, "alice", tokens["alice"], second); got.Code != http.StatusOK {
		t.Fatalf("replacement session /api/me status = %d, want 200", got.Code)
	}
}

func TestManagedDeviceRegistrationRequiresCurrentSessionDevice(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true})
	first := claimHTTPSession(t, server, "alice", tokens["alice"], "phone")

	valid := requestWithSession(t, server, http.MethodPut, "/api/device", []byte(`{"device_id":"phone","fcm_token":"phone-fcm"}`), "alice", tokens["alice"], first)
	if valid.Code != http.StatusNoContent {
		t.Fatalf("current phone registration status = %d, body %s", valid.Code, valid.Body.String())
	}
	mismatch := requestWithSession(t, server, http.MethodPut, "/api/device", []byte(`{"device_id":"tablet","fcm_token":"wrong-fcm"}`), "alice", tokens["alice"], first)
	assertSessionReplacedResponse(t, "mismatched device", mismatch)

	second := claimHTTPSession(t, server, "alice", tokens["alice"], "tablet")
	stale := requestWithSession(t, server, http.MethodPut, "/api/device", []byte(`{"device_id":"phone","fcm_token":"stale-fcm"}`), "alice", tokens["alice"], first)
	assertSessionReplacedResponse(t, "stale device request", stale)
	current := requestWithSession(t, server, http.MethodPut, "/api/device", []byte(`{"device_id":"tablet","fcm_token":"tablet-fcm"}`), "alice", tokens["alice"], second)
	if current.Code != http.StatusNoContent {
		t.Fatalf("replacement tablet registration status = %d, body %s", current.Code, current.Body.String())
	}
	devices, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].DeviceID != "tablet" || devices[0].PushTarget != (state.PushTarget{Kind: state.KindToken, Value: "tablet-fcm"}) {
		t.Fatalf("managed devices = %+v, want only current tablet", devices)
	}
}

func TestSocketRejectsStaleSessionAndClaimClosesExistingConnection(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	handler := NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub})
	server := httptest.NewServer(handler)
	defer server.Close()
	first := claimHTTPSession(t, handler, "alice", tokens["alice"], "phone")

	for name, sessionID := range map[string]string{"missing": "", "stale": "stale-session"} {
		conn, response, err := tryDialSessionSocket(server.URL, "alice", tokens["alice"], "phone", sessionID)
		if conn != nil {
			_ = conn.Close()
		}
		if err == nil || response == nil {
			t.Fatalf("%s socket response = %+v error %v, want HTTP 401", name, response, err)
		}
		if response.StatusCode != http.StatusUnauthorized || response.Header.Get(testAuthReasonHeader) != testReplacedReason {
			t.Fatalf("%s socket status/reason = %d/%q, want 401/session_replaced", name, response.StatusCode, response.Header.Get(testAuthReasonHeader))
		}
		_ = response.Body.Close()
	}
	current, _, err := tryDialSessionSocket(server.URL, "alice", tokens["alice"], "phone", first)
	if err != nil {
		t.Fatal(err)
	}
	defer current.Close()

	second := claimHTTPSession(t, handler, "alice", tokens["alice"], "tablet")
	if err := current.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := current.ReadMessage(); err == nil {
		t.Fatal("replaced websocket remained open when claim returned")
	}
	stale, response, err := tryDialSessionSocket(server.URL, "alice", tokens["alice"], "phone", first)
	if stale != nil {
		_ = stale.Close()
	}
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized || response.Header.Get(testAuthReasonHeader) != testReplacedReason {
		t.Fatalf("stale reconnect response = %+v error %v, want 401/session_replaced", response, err)
	}
	_ = response.Body.Close()
	replacement, _, err := tryDialSessionSocket(server.URL, "alice", tokens["alice"], "tablet", second)
	if err != nil {
		t.Fatal(err)
	}
	_ = replacement.Close()
}

func TestSessionClaimDoesNotWaitForTargetedPushDelivery(t *testing.T) {
	db, tokens := testDB(t)
	if err := db.UpsertDevice("alice", "old-phone", "old-fcm"); err != nil {
		t.Fatal(err)
	}
	notifier := &blockingSessionNotifier{started: make(chan struct{}), release: make(chan struct{})}
	handler := NewServer(db, Options{AllowInsecureLoopback: true, SessionNotifier: notifier})
	req := httptest.NewRequest(http.MethodPost, "/api/session", bytes.NewBufferString(`{"device_id":"tablet"}`))
	req.SetBasicAuth("alice", tokens["alice"])
	recorder := httptest.NewRecorder()
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		handler.ServeHTTP(recorder, req)
		response <- recorder
	}()
	select {
	case <-notifier.started:
	case <-time.After(time.Second):
		t.Fatal("targeted replacement push was not started")
	}
	defer close(notifier.release)
	select {
	case got := <-response:
		if got.Code != http.StatusOK {
			t.Fatalf("claim status = %d, body %s", got.Code, got.Body.String())
		}
	case <-time.After(time.Second):
		t.Fatal("claim response waited for targeted push delivery")
	}
}

func claimHTTPSession(t *testing.T, handler http.Handler, login, token, deviceID string) string {
	t.Helper()
	response := requestWithSession(t, handler, http.MethodPost, "/api/session", []byte(`{"device_id":"`+deviceID+`"}`), login, token, "")
	if response.Code != http.StatusOK {
		t.Fatalf("claim %s status = %d, body %s", deviceID, response.Code, response.Body.String())
	}
	var payload struct {
		SessionID string `json:"session_id"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.SessionID == "" {
		t.Fatal("claim returned an empty session_id")
	}
	return payload.SessionID
}

func requestWithSession(t *testing.T, handler http.Handler, method, path string, body []byte, login, token, sessionID string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(login+":"+token)))
	if sessionID != "" {
		req.Header.Set(testSessionHeader, sessionID)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	return recorder
}

func assertSessionReplacedResponse(t *testing.T, name string, response *httptest.ResponseRecorder) {
	t.Helper()
	if response.Code != http.StatusUnauthorized || response.Header().Get(testAuthReasonHeader) != testReplacedReason {
		t.Fatalf("%s status/reason = %d/%q, want 401/session_replaced", name, response.Code, response.Header().Get(testAuthReasonHeader))
	}
}

func tryDialSessionSocket(baseURL, login, token, deviceID, sessionID string) (*websocket.Conn, *http.Response, error) {
	header := http.Header{}
	header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(login+":"+token)))
	header.Set(signalProtocolHeader, signalProtocolVersion)
	header.Set(deviceIDHeader, deviceID)
	if sessionID != "" {
		header.Set(testSessionHeader, sessionID)
	}
	url := "ws" + baseURL[len("http"):] + "/api/socket"
	return websocket.DefaultDialer.Dial(url, header)
}

type blockingSessionNotifier struct {
	started chan struct{}
	release chan struct{}
}

func (n *blockingSessionNotifier) SessionReplaced(_ string, _ string, _ []state.Device) {
	close(n.started)
	<-n.release
}
