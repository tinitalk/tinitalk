package httpapi

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func TestAuthenticatedHouseholdEndpoints(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true})

	me := request(t, server, http.MethodGet, "/api/me", nil, "alice", tokens["alice"])
	if me.Code != http.StatusOK {
		t.Fatalf("/api/me status = %d, body %s", me.Code, me.Body.String())
	}
	var profile map[string]string
	if err := json.Unmarshal(me.Body.Bytes(), &profile); err != nil {
		t.Fatal(err)
	}
	if profile["login"] != "alice" || profile["display_name"] != "Alice" {
		t.Fatalf("profile = %+v", profile)
	}

	contacts := request(t, server, http.MethodGet, "/api/contacts", nil, "alice", tokens["alice"])
	if contacts.Code != http.StatusOK {
		t.Fatalf("/api/contacts status = %d, body %s", contacts.Code, contacts.Body.String())
	}
	if bytes.Contains(contacts.Body.Bytes(), []byte(`"login":"alice"`)) {
		t.Fatalf("contacts includes self: %s", contacts.Body.String())
	}
	if !bytes.Contains(contacts.Body.Bytes(), []byte(`"login":"bob"`)) {
		t.Fatalf("contacts missing bob: %s", contacts.Body.String())
	}

	device := request(t, server, http.MethodPut, "/api/device", []byte(`{"device_id":"phone","fcm_token":"secret-fcm"}`), "alice", tokens["alice"])
	if device.Code != http.StatusNoContent {
		t.Fatalf("/api/device status = %d, body %s", device.Code, device.Body.String())
	}
	if bytes.Contains(device.Body.Bytes(), []byte("secret-fcm")) {
		t.Fatalf("device response leaked token: %s", device.Body.String())
	}
}

func TestHealthIdentifiesTiniTalkAndItsAPIVersion(t *testing.T) {
	db, _ := testDB(t)
	server := NewServer(db, Options{})

	response := request(t, server, http.MethodGet, "/healthz", nil, "", "")
	if response.Code != http.StatusOK {
		t.Fatalf("/healthz status = %d, body %s", response.Code, response.Body.String())
	}
	var health struct {
		Service    string `json:"service"`
		Status     string `json:"status"`
		APIVersion int    `json:"api_version"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &health); err != nil {
		t.Fatal(err)
	}
	if health.Service != "tinitalk" || health.Status != "ok" || health.APIVersion != 1 {
		t.Fatalf("health = %+v, want tinitalk, ok, API version 1", health)
	}
}

func TestContactNamesArePersonalAndResettable(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true})

	renamed := request(t, server, http.MethodPut, "/api/contacts/bob/name", []byte(`{"custom_name":"  Мама  "}`), "alice", tokens["alice"])
	if renamed.Code != http.StatusOK {
		t.Fatalf("rename status = %d, body %s", renamed.Code, renamed.Body.String())
	}
	var contact struct {
		Login              string  `json:"login"`
		DisplayName        string  `json:"display_name"`
		DefaultDisplayName string  `json:"default_display_name"`
		CustomName         *string `json:"custom_name"`
	}
	if err := json.Unmarshal(renamed.Body.Bytes(), &contact); err != nil {
		t.Fatal(err)
	}
	if contact.Login != "bob" || contact.DisplayName != "Мама" || contact.DefaultDisplayName != "Bob" || contact.CustomName == nil || *contact.CustomName != "Мама" {
		t.Fatalf("renamed contact = %+v", contact)
	}

	aliceContacts := request(t, server, http.MethodGet, "/api/contacts", nil, "alice", tokens["alice"])
	if aliceContacts.Code != http.StatusOK {
		t.Fatalf("alice contacts status = %d, body %s", aliceContacts.Code, aliceContacts.Body.String())
	}
	if !bytes.Contains(aliceContacts.Body.Bytes(), []byte(`"display_name":"Мама"`)) {
		t.Fatalf("alice contacts = %s, want personal name", aliceContacts.Body.String())
	}
	bobContacts := request(t, server, http.MethodGet, "/api/contacts", nil, "bob", tokens["bob"])
	if bobContacts.Code != http.StatusOK {
		t.Fatalf("bob contacts status = %d, body %s", bobContacts.Code, bobContacts.Body.String())
	}
	if bytes.Contains(bobContacts.Body.Bytes(), []byte(`"display_name":"Мама"`)) {
		t.Fatalf("bob contacts leaked alice's personal name: %s", bobContacts.Body.String())
	}

	reset := request(t, server, http.MethodPut, "/api/contacts/bob/name", []byte(`{"custom_name":null}`), "alice", tokens["alice"])
	if reset.Code != http.StatusOK {
		t.Fatalf("reset status = %d, body %s", reset.Code, reset.Body.String())
	}
	if err := json.Unmarshal(reset.Body.Bytes(), &contact); err != nil {
		t.Fatal(err)
	}
	if contact.DisplayName != "Bob" || contact.CustomName != nil {
		t.Fatalf("reset contact = %+v", contact)
	}

	invalid := request(t, server, http.MethodPut, "/api/contacts/bob/name", []byte(`{"custom_name":"   "}`), "alice", tokens["alice"])
	if invalid.Code != http.StatusBadRequest {
		t.Fatalf("blank name status = %d, want 400", invalid.Code)
	}
}

func TestContactsPageReturnsTwentyContactsAtATime(t *testing.T) {
	db, tokens := testDB(t)
	for i := 0; i < 23; i++ {
		if _, err := db.AddUser(fmt.Sprintf("user%02d", i), fmt.Sprintf("Person %02d", i)); err != nil {
			t.Fatal(err)
		}
	}
	server := NewServer(db, Options{AllowInsecureLoopback: true})

	first := request(t, server, http.MethodGet, "/api/contacts/page?limit=20", nil, "alice", tokens["alice"])
	if first.Code != http.StatusOK {
		t.Fatalf("first page status = %d, body %s", first.Code, first.Body.String())
	}
	var firstPage struct {
		Items      []contactResponse `json:"items"`
		NextCursor string            `json:"next_cursor"`
	}
	if err := json.Unmarshal(first.Body.Bytes(), &firstPage); err != nil {
		t.Fatal(err)
	}
	if len(firstPage.Items) != 20 || firstPage.NextCursor == "" {
		t.Fatalf("first page has %d items and cursor %q, want 20 items and a cursor", len(firstPage.Items), firstPage.NextCursor)
	}

	second := request(t, server, http.MethodGet, "/api/contacts/page?limit=20&cursor="+firstPage.NextCursor, nil, "alice", tokens["alice"])
	if second.Code != http.StatusOK {
		t.Fatalf("second page status = %d, body %s", second.Code, second.Body.String())
	}
	var secondPage struct {
		Items      []contactResponse `json:"items"`
		NextCursor string            `json:"next_cursor"`
	}
	if err := json.Unmarshal(second.Body.Bytes(), &secondPage); err != nil {
		t.Fatal(err)
	}
	if len(secondPage.Items) != 4 || secondPage.NextCursor != "" {
		t.Fatalf("second page has %d items and cursor %q, want 4 and no cursor", len(secondPage.Items), secondPage.NextCursor)
	}
	if firstPage.Items[19].Login == secondPage.Items[0].Login {
		t.Fatalf("pages overlap at %q", secondPage.Items[0].Login)
	}
}

func TestUnauthorizedCredentialsReturn401(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true})
	if got := request(t, server, http.MethodGet, "/api/me", nil, "mallory", "bad"); got.Code != http.StatusUnauthorized {
		t.Fatalf("unknown credentials status = %d", got.Code)
	}
	old := tokens["alice"]
	if _, err := db.RotateToken("alice"); err != nil {
		t.Fatal(err)
	}
	if got := request(t, server, http.MethodGet, "/api/me", nil, "alice", old); got.Code != http.StatusUnauthorized {
		t.Fatalf("rotated credentials status = %d", got.Code)
	}
	if err := db.DisableUser("bob"); err != nil {
		t.Fatal(err)
	}
	if got := request(t, server, http.MethodGet, "/api/me", nil, "bob", tokens["bob"]); got.Code != http.StatusUnauthorized {
		t.Fatalf("disabled credentials status = %d", got.Code)
	}
}

func TestRejectsBasicAuthWithoutTLSExceptLoopbackMode(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{})
	got := request(t, server, http.MethodGet, "/api/me", nil, "alice", tokens["alice"])
	if got.Code != http.StatusUnauthorized {
		t.Fatalf("plain request status = %d", got.Code)
	}
}

func TestSocketRoutesCallEvents(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	server := httptest.NewServer(NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}))
	defer server.Close()

	alice := dialSocket(t, server.URL, "alice", tokens["alice"])
	defer alice.Close()
	bob := dialSocket(t, server.URL, "bob", tokens["bob"])
	defer bob.Close()

	if err := alice.WriteJSON(map[string]any{
		"id":      "018f7d51-3f90-7e63-b657-4a83a6a90301",
		"call_id": "018f7d51-40a1-7bb5-a2d0-7e47f9180301",
		"type":    "call.start",
		"sent_at": 1787666400000,
		"payload": map[string]any{"callee_id": "bob"},
	}); err != nil {
		t.Fatal(err)
	}
	var incoming map[string]any
	if err := bob.ReadJSON(&incoming); err != nil {
		t.Fatal(err)
	}
	if incoming["type"] != "call.incoming" || incoming["seq"].(float64) != 1 {
		t.Fatalf("incoming = %+v", incoming)
	}

	missingCallID := "018f7d51-40a1-7bb5-a2d0-7e47f9180399"
	if err := alice.WriteJSON(map[string]any{
		"id":      "018f7d51-3f90-7e63-b657-4a83a6a90399",
		"call_id": missingCallID,
		"type":    "call.accept",
		"sent_at": 1787666400000,
		"payload": map[string]any{},
	}); err != nil {
		t.Fatal(err)
	}
	var failure map[string]any
	if err := alice.ReadJSON(&failure); err != nil {
		t.Fatal(err)
	}
	if failure["error"] != "call not found" || failure["call_id"] != missingCallID {
		t.Fatalf("failure = %+v", failure)
	}
}

func TestSocketRejectsConnectionAbovePerUserLimit(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	server := httptest.NewServer(NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}))
	defer server.Close()

	connections := make([]*websocket.Conn, 0, signaling.MaxConnectionsPerUser)
	for range signaling.MaxConnectionsPerUser {
		connections = append(connections, dialSocket(t, server.URL, "alice", tokens["alice"]))
	}
	defer func() {
		for _, conn := range connections {
			_ = conn.Close()
		}
	}()

	header := http.Header{}
	header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte("alice:"+tokens["alice"])))
	url := "ws" + server.URL[len("http"):] + "/api/socket"
	conn, response, err := websocket.DefaultDialer.Dial(url, header)
	if conn != nil {
		_ = conn.Close()
	}
	if err == nil {
		t.Fatal("connection above limit succeeded")
	}
	if response == nil || response.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("response = %+v, want status %d", response, http.StatusTooManyRequests)
	}
}

func TestSocketHeartbeatReleasesUnresponsiveConnections(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	s := NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}).(*Server)
	s.socketTiming = socketTiming{20 * time.Millisecond, 80 * time.Millisecond, 20 * time.Millisecond}
	server := httptest.NewServer(s)
	defer server.Close()

	for range signaling.MaxConnectionsPerUser {
		conn := dialSocket(t, server.URL, "alice", tokens["alice"])
		defer conn.Close()
	}

	deadline := time.Now().Add(s.socketTiming.pongTimeout + 4*s.socketTiming.pingInterval)
	for time.Now().Before(deadline) {
		conn, response, err := tryDialSocket(server.URL, "alice", tokens["alice"])
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		if err == nil {
			_ = conn.Close()
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("unresponsive connections did not release a websocket slot")
}

func testDB(t *testing.T) (*state.DB, map[string]string) {
	t.Helper()
	db, err := state.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := db.Init(nil); err != nil {
		t.Fatal(err)
	}
	tokens := map[string]string{}
	for _, user := range []struct{ login, name string }{{"alice", "Alice"}, {"bob", "Bob"}} {
		token, err := db.AddUser(user.login, user.name)
		if err != nil {
			t.Fatal(err)
		}
		tokens[user.login] = token
	}
	return db, tokens
}

func request(t *testing.T, h http.Handler, method, path string, body []byte, login, token string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(login+":"+token)))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func dialSocket(t *testing.T, baseURL, login, token string) *websocket.Conn {
	t.Helper()
	conn, _, err := tryDialSocket(baseURL, login, token)
	if err != nil {
		t.Fatal(err)
	}
	return conn
}

func tryDialSocket(baseURL, login, token string) (*websocket.Conn, *http.Response, error) {
	header := http.Header{}
	header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(login+":"+token)))
	url := "ws" + baseURL[len("http"):] + "/api/socket"
	return websocket.DefaultDialer.Dial(url, header)
}
