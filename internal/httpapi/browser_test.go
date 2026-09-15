package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
)

func TestBrowserTicketCrossOriginSingleUseAndSessionBinding(t *testing.T) {
	db, tokens := testDB(t)
	s := NewServer(db, Options{AllowInsecureLoopback: true, Hub: signaling.NewHub(signaling.NoopNotifier{})}).(*Server)
	server := httptest.NewServer(s)
	defer server.Close()
	claim := request(t, s, "POST", "/api/browser/session", []byte(`{"device_id":"browser-a"}`), "alice", tokens["alice"])
	if claim.Code != 200 {
		t.Fatalf("claim: %d %s", claim.Code, claim.Body)
	}
	var session struct {
		SessionID string `json:"session_id"`
	}
	if err := json.Unmarshal(claim.Body.Bytes(), &session); err != nil {
		t.Fatal(err)
	}
	issue := func() string {
		r := httptest.NewRequest("POST", "/api/browser/socket-ticket", bytes.NewBufferString("{}"))
		r.SetBasicAuth("alice", tokens["alice"])
		r.Header.Set("Origin", "https://official.example")
		r.Header.Set(deviceIDHeader, "browser-a")
		r.Header.Set(sessionIDHeader, session.SessionID)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatalf("ticket: %d %s", w.Code, w.Body)
		}
		var body struct {
			Ticket                      string `json:"ticket"`
			ForegroundCallNotifications bool   `json:"foreground_call_notifications"`
		}
		_ = json.Unmarshal(w.Body.Bytes(), &body)
		if !body.ForegroundCallNotifications {
			t.Fatal("browser cannot negotiate foreground acknowledgements")
		}
		return body.Ticket
	}
	dial := func(key, origin string, want bool) {
		t.Helper()
		d := websocket.Dialer{Subprotocols: []string{browserProtocol, "ticket." + key}}
		conn, response, err := d.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/browser/socket?foreground_call_notifications=1", http.Header{"Origin": []string{origin}})
		if conn != nil {
			defer conn.Close()
		}
		if response != nil && response.Body != nil {
			defer response.Body.Close()
		}
		if (err == nil) != want {
			t.Fatalf("dial allowed=%v error=%v", want, err)
		}
		if want && conn.Subprotocol() != browserProtocol {
			t.Fatalf("subprotocol = %q", conn.Subprotocol())
		}
		if want {
			// A visibility heartbeat racing an already-removed call is harmless.
			id := "018f7d51-40a1-7bb5-a2d0-7e47f9180999"
			if err := conn.WriteJSON(protocol.Event{ID: id, CallID: id, Type: "call.visibility", Payload: json.RawMessage(`{"visible":false}`)}); err != nil {
				t.Fatal(err)
			}
			_ = conn.SetReadDeadline(time.Now().Add(time.Second))
			var frame map[string]any
			if err := conn.ReadJSON(&frame); err != nil {
				t.Fatal(err)
			}
			if frame["ack"] != id {
				t.Fatalf("visibility not negotiated: %v", frame)
			}
		}
	}
	key := issue()
	dial(key, "https://official.example", true)
	dial(key, "https://official.example", false)
	dial(issue(), "https://other.example", false)
	key = issue()
	s.tickets.Lock()
	entry := s.tickets.items[key]
	entry.expires = time.Now().Add(-time.Second)
	s.tickets.items[key] = entry
	s.tickets.Unlock()
	dial(key, "https://official.example", false)
	key = issue()
	if _, err := db.ClaimSession("alice", "another-phone"); err != nil {
		t.Fatal(err)
	}
	dial(key, "https://official.example", false)
}

func TestBrowserCORSPreflightDoesNotRequireAuthButAPIStillDoes(t *testing.T) {
	db, _ := testDB(t)
	s := NewServer(db, Options{AllowInsecureLoopback: true})
	for _, origin := range []string{"https://family.example", "https://official.example"} {
		r := httptest.NewRequest("OPTIONS", "/api/browser/session", nil)
		r.Header.Set("Origin", origin)
		r.Header.Set("Access-Control-Request-Headers", "authorization,content-type")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 204 || w.Header().Get("Access-Control-Allow-Origin") != "*" || w.Header().Get("Access-Control-Allow-Credentials") != "" {
			t.Fatalf("preflight: %d %v", w.Code, w.Header())
		}
		r = httptest.NewRequest("GET", "/api/contacts", nil)
		r.Header.Set("Origin", origin)
		r.Header.Set(deviceIDHeader, "browser-a")
		w = httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 401 {
			t.Fatalf("anonymous request: %d", w.Code)
		}
	}
	for _, origin := range []string{"null", "http://untrusted.example", "https://example.org/path"} {
		r := httptest.NewRequest("OPTIONS", "/api/contacts", nil)
		r.Header.Set("Origin", origin)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatalf("accepted origin %s", origin)
		}
	}
}

func TestBrowserAccountsHaveIndependentSessionsAndPushTargets(t *testing.T) {
	db, tokens := testDB(t)
	s := NewServer(db, Options{AllowInsecureLoopback: true, WebPushConfigID: "sha256:webpush"})
	for _, login := range []string{"alice", "bob"} {
		body := []byte(`{"device_id":"web-` + login + `","config_id":"sha256:webpush","webpush_subscription":{"client_type":"web","endpoint":"https://web.push.apple.com/` + login + `","keys":{"auth":"AAAAAAAAAAAAAAAAAAAAAA","p256dh":"BEkDdNnpEcD8M4mRGOFJWTDJ4GkDI5Xs3vpIOrAaBZKRCVv6V3sB3CFujTFiD6DHda7W8pCyChJDU205otrbCAw"}}}`)
		w := request(t, s, "POST", "/api/browser/session", body, login, tokens[login])
		if w.Code != 200 {
			t.Fatalf("%s: %d %s", login, w.Code, w.Body)
		}
	}
	for _, login := range []string{"alice", "bob"} {
		targets, err := db.PushTargetsForUser(login)
		if err != nil || len(targets) != 1 {
			t.Fatalf("%s: %v %v", login, targets, err)
		}
	}
}

func TestBrowserAndNativeAudioSignaling(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	s := NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub})
	server := httptest.NewServer(s)
	defer server.Close()
	claim := request(t, s, "POST", "/api/browser/session", []byte(`{"device_id":"web-alice"}`), "alice", tokens["alice"])
	var session struct {
		SessionID string `json:"session_id"`
	}
	if claim.Code != 200 || json.Unmarshal(claim.Body.Bytes(), &session) != nil {
		t.Fatalf("claim: %s", claim.Body)
	}
	r := httptest.NewRequest("POST", "/api/browser/socket-ticket", nil)
	r.SetBasicAuth("alice", tokens["alice"])
	r.Header.Set("Origin", "https://static.example")
	r.Header.Set(deviceIDHeader, "web-alice")
	r.Header.Set(sessionIDHeader, session.SessionID)
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	var ticket struct {
		Ticket string `json:"ticket"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &ticket) != nil {
		t.Fatalf("ticket: %s", w.Body)
	}
	d := websocket.Dialer{Subprotocols: []string{browserProtocol, "ticket." + ticket.Ticket}}
	browser, _, err := d.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/browser/socket", http.Header{"Origin": []string{"https://static.example"}})
	if err != nil {
		t.Fatal(err)
	}
	defer browser.Close()
	native := dialDeviceSocket(t, server.URL, "bob", tokens["bob"], "android-bob")
	defer native.Close()
	readType := func(conn *websocket.Conn, kind string) map[string]any {
		t.Helper()
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		for {
			event := readSocketEvent(t, conn)
			if _, ack := event["ack"]; ack {
				continue
			}
			if event["type"] != kind {
				t.Fatalf("want %s, got %#v", kind, event)
			}
			return event
		}
	}
	callID := "018f7d51-3f90-7e63-b657-4a83a6a90801"
	writeSocketEvent(t, browser, "018f7d51-3f90-7e63-b657-4a83a6a90802", callID, "call.start", map[string]any{"callee_id": "bob", "caller_login": "spoofed", "supports_video": false, "supports_call_sas": false})
	incoming := readType(native, "call.incoming")
	if incoming["payload"].(map[string]any)["caller_login"] != "alice" {
		t.Fatalf("caller identity: %#v", incoming)
	}
	writeSocketEvent(t, native, "018f7d51-3f90-7e63-b657-4a83a6a90803", callID, "call.accept", map[string]any{"supports_video": true, "supports_call_sas": true})
	readType(browser, "call.accept")
	for _, conn := range []*websocket.Conn{browser, native} {
		config := readType(conn, "rtc.config")["payload"].(map[string]any)
		if config["video_allowed"] != false || config["call_sas_allowed"] != false {
			t.Fatalf("capabilities: %#v", config)
		}
	}
	writeSocketEvent(t, browser, "018f7d51-3f90-7e63-b657-4a83a6a90804", callID, "rtc.offer", map[string]any{"sdp": "test-offer"})
	readType(native, "rtc.offer")
	writeSocketEvent(t, native, "018f7d51-3f90-7e63-b657-4a83a6a90805", callID, "rtc.answer", map[string]any{"sdp": "test-answer"})
	readType(browser, "rtc.answer")
	writeSocketEvent(t, browser, "018f7d51-3f90-7e63-b657-4a83a6a90806", callID, "call.end", map[string]any{})
	readType(native, "call.end")
}

func TestBrowserRejectsAmbientBasicAuthWithoutExplicitDeviceHeader(t *testing.T) {
	db, tokens := testDB(t)
	s := NewServer(db, Options{AllowInsecureLoopback: true})
	r := httptest.NewRequest("POST", "/api/browser/session", strings.NewReader(`{"device_id":"attacker"}`))
	r.Header.Set("Origin", "https://untrusted.example")
	r.SetBasicAuth("alice", tokens["alice"])
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("ambient authentication accepted: %d", w.Code)
	}
}
