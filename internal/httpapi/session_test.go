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

func TestSessionClaimFIDActivationValidatesShapeAndConfigBeforeMutation(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true, FirebaseConfig: firebaseConfigForTest()})
	legacy := requestWithSession(t, server, http.MethodPost, "/api/session", []byte(`{"device_id":"phone"}`), "alice", tokens["alice"], "")
	if legacy.Code != http.StatusOK {
		t.Fatalf("legacy session status = %d, body %s", legacy.Code, legacy.Body.String())
	}
	var legacyBody map[string]string
	if err := json.Unmarshal(legacy.Body.Bytes(), &legacyBody); err != nil {
		t.Fatal(err)
	}
	if len(legacyBody) != 1 || legacyBody["session_id"] == "" {
		t.Fatalf("legacy session response = %#v, want only a session_id", legacyBody)
	}
	for name, body := range map[string]string{
		"partial fid":      `{"device_id":"tablet","firebase_installation_id":"fid"}`,
		"null config":      `{"device_id":"tablet","config_id":null}`,
		"mixed":            `{"device_id":"tablet","fcm_token":"token","firebase_installation_id":"fid","config_id":"config-id"}`,
		"mixed null token": `{"device_id":"tablet","fcm_token":null,"firebase_installation_id":"fid","config_id":"config-id"}`,
	} {
		if got := requestWithSession(t, server, http.MethodPost, "/api/session", []byte(body), "alice", tokens["alice"], ""); got.Code != http.StatusBadRequest {
			t.Fatalf("%s session status = %d, want 400", name, got.Code)
		}
	}
	stale := requestWithSession(t, server, http.MethodPost, "/api/session", []byte(`{"device_id":"tablet","firebase_installation_id":"fid","config_id":"stale-config"}`), "alice", tokens["alice"], "")
	if stale.Code != http.StatusConflict {
		t.Fatalf("stale config session status = %d, want 409", stale.Code)
	}
	current, managed, err := db.CurrentSession("alice")
	if err != nil {
		t.Fatal(err)
	}
	if !managed || current.SessionID != legacyBody["session_id"] || current.DeviceID != "phone" {
		t.Fatalf("session after stale config = %+v, managed %v, want phone legacy session", current, managed)
	}
	activated := requestWithSession(t, server, http.MethodPost, "/api/session", []byte(`{"device_id":"tablet","firebase_installation_id":"first-fid","config_id":"config-id"}`), "alice", tokens["alice"], "")
	if activated.Code != http.StatusOK {
		t.Fatalf("FID session status = %d, body %s", activated.Code, activated.Body.String())
	}
	var activatedBody map[string]string
	if err := json.Unmarshal(activated.Body.Bytes(), &activatedBody); err != nil {
		t.Fatal(err)
	}
	if len(activatedBody) != 1 || activatedBody["session_id"] == "" || activatedBody["session_id"] == legacyBody["session_id"] {
		t.Fatalf("FID session response = %#v, want one new session_id", activatedBody)
	}
	retry := requestWithSession(t, server, http.MethodPost, "/api/session", []byte(`{"device_id":"tablet","firebase_installation_id":"second-fid","config_id":"config-id"}`), "alice", tokens["alice"], "")
	if retry.Code != http.StatusOK {
		t.Fatalf("FID retry status = %d, body %s", retry.Code, retry.Body.String())
	}
	var retryBody map[string]string
	if err := json.Unmarshal(retry.Body.Bytes(), &retryBody); err != nil {
		t.Fatal(err)
	}
	if retryBody["session_id"] != activatedBody["session_id"] {
		t.Fatalf("FID retry session = %#v, want %q", retryBody, activatedBody["session_id"])
	}
	devices, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	want := state.PushTarget{Kind: state.KindFID, Value: "second-fid", ConfigID: "config-id"}
	if len(devices) != 1 || devices[0].DeviceID != "tablet" || devices[0].PushTarget != want {
		t.Fatalf("FID activation devices = %+v, want tablet %+v", devices, want)
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
	if valid.Body.Len() != 0 {
		t.Fatalf("legacy device response body = %q, want empty", valid.Body.String())
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

func TestDeviceFIDRegistrationValidatesShapeAndConfigBeforeMutation(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true, FirebaseConfig: firebaseConfigForTest()})
	for name, body := range map[string]string{
		"partial fid":        `{"device_id":"phone","firebase_installation_id":"fid"}`,
		"legacy null config": `{"device_id":"phone","fcm_token":"token","config_id":null}`,
		"mixed":              `{"device_id":"phone","fcm_token":"token","firebase_installation_id":"fid","config_id":"config-id"}`,
		"mixed null token":   `{"device_id":"phone","fcm_token":null,"firebase_installation_id":"fid","config_id":"config-id"}`,
	} {
		if got := request(t, server, http.MethodPut, "/api/device", []byte(body), "alice", tokens["alice"]); got.Code != http.StatusBadRequest {
			t.Fatalf("%s device status = %d, want 400", name, got.Code)
		}
	}
	stale := request(t, server, http.MethodPut, "/api/device", []byte(`{"device_id":"phone","firebase_installation_id":"fid","config_id":"stale-config"}`), "alice", tokens["alice"])
	if stale.Code != http.StatusConflict {
		t.Fatalf("stale config device status = %d, want 409", stale.Code)
	}
	devices, err := db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 0 {
		t.Fatalf("devices after stale config = %+v, want unchanged empty targets", devices)
	}
	activated := request(t, server, http.MethodPut, "/api/device", []byte(`{"device_id":"phone","firebase_installation_id":"fid","config_id":"config-id"}`), "alice", tokens["alice"])
	if activated.Code != http.StatusNoContent || activated.Body.Len() != 0 {
		t.Fatalf("FID device response = %d/%q, want 204/empty", activated.Code, activated.Body.String())
	}
	devices, err = db.PushTargetsForUser("alice")
	if err != nil {
		t.Fatal(err)
	}
	want := state.PushTarget{Kind: state.KindFID, Value: "fid", ConfigID: "config-id"}
	if len(devices) != 1 || devices[0].PushTarget != want {
		t.Fatalf("FID device targets = %+v, want %+v", devices, want)
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
