package httpapi

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

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
	header := http.Header{}
	header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(login+":"+token)))
	url := "ws" + baseURL[len("http"):] + "/api/socket"
	conn, _, err := websocket.DefaultDialer.Dial(url, header)
	if err != nil {
		t.Fatal(err)
	}
	return conn
}
