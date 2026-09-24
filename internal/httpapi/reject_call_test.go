package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func TestRejectCallFromNotification(t *testing.T) {
	for _, scenario := range []string{"background", "waiting", "closed", "accepted", "cancelled", "expired", "unknown", "caller", "third-party", "wrong-device", "stale-session", "missing-session", "invalid-id"} {
		t.Run(scenario, func(t *testing.T) {
			db, tokens := testDB(t)
			hub := signaling.NewHub(nil)
			hub.SetCallHistoryStore(db)
			handler := NewServer(db, Options{AllowInsecureLoopback: true, WebPushConfigID: "sha256:webpush", Hub: hub})
			session := claimHTTPSession(t, handler, "bob", tokens["bob"], "phone")
			alice := hub.Connect("alice")
			var bob *signaling.Client
			if scenario != "closed" {
				var err error
				bob, err = hub.ConnectSessionChecked("bob", "phone", session)
				if err != nil || !hub.Connected(bob) {
					t.Fatalf("connect: %v", err)
				}
			}
			callID := "018f7d51-40a1-7bb5-a2d0-7e47f9180101"
			send := func(sender, kind, payload, id string) {
				t.Helper()
				if err := hub.Handle(sender, protocol.Event{ID: id, CallID: callID, Type: kind, Payload: []byte(payload)}); err != nil {
					t.Fatal(err)
				}
			}
			if scenario == "waiting" {
				hub.EnableCallWaiting(bob)
			}
			send("alice", "call.start", `{"callee_id":"bob"}`, "018f7d51-3f90-7e63-b657-4a83a6a90101")
			login, token, device := "bob", tokens["bob"], "phone"
			want := http.StatusNoContent
			switch scenario {
			case "waiting":
				if err := hub.HandleClient(bob, protocol.Event{ID: "018f7d51-3f90-7e63-b657-4a83a6a90102", CallID: callID, Type: "call.waiting", Payload: []byte(`{"waiting":true}`)}); err != nil {
					t.Fatal(err)
				}
			case "accepted":
				send("bob", "call.accept", `{}`, "018f7d51-3f90-7e63-b657-4a83a6a90102")
			case "cancelled":
				send("alice", "call.cancel", `{}`, "018f7d51-3f90-7e63-b657-4a83a6a90102")
			case "expired":
				hub.SetNow(func() time.Time { return time.Now().Add(time.Minute) })
			case "unknown":
				callID = "018f7d51-40a1-7bb5-a2d0-7e47f9180199"
			case "caller":
				login, token = "alice", tokens["alice"]
				session = claimHTTPSession(t, handler, login, token, device)
				want = http.StatusForbidden
			case "third-party":
				var err error
				login = "eve"
				token, err = db.AddUser(login, "Eve")
				if err != nil {
					t.Fatal(err)
				}
				session = claimHTTPSession(t, handler, login, token, device)
				want = http.StatusForbidden
			case "wrong-device":
				device, want = "tablet", http.StatusUnauthorized
			case "stale-session":
				session, want = "old-session", http.StatusUnauthorized
			case "missing-session":
				session, want = "", http.StatusUnauthorized
			case "invalid-id":
				callID, want = "invalid", http.StatusBadRequest
			}
			drain := func(client *signaling.Client) {
				if client != nil {
					for {
						if _, ok := client.TryNext(); !ok {
							break
						}
					}
				}
			}
			drain(alice)
			drain(bob)
			reject := func() *httptest.ResponseRecorder {
				req := httptest.NewRequest(http.MethodPost, "/api/calls/"+callID+"/reject", nil)
				req.SetBasicAuth(login, token)
				req.Header.Set(sessionIDHeader, session)
				req.Header.Set(deviceIDHeader, device)
				req.Header.Set("Origin", "https://another-pwa.example")
				response := httptest.NewRecorder()
				handler.ServeHTTP(response, req)
				return response
			}
			response := reject()
			if response.Code != want {
				t.Fatalf("status = %d, want %d: %s", response.Code, want, response.Body)
			}
			if response.Header().Get("Access-Control-Allow-Origin") != "*" {
				t.Fatal("cross-origin request not allowed")
			}
			declined := scenario == "background" || scenario == "closed" || scenario == "waiting"
			for _, client := range []*signaling.Client{alice, bob} {
				if client == nil {
					continue
				}
				event, ok := client.TryNext()
				if declined && (!ok || event.Type != "call.reject" || event.CallID != callID) {
					t.Fatalf("terminal event = %+v, present %v", event, ok)
				}
				if !declined && ok {
					t.Fatalf("unexpected event: %+v", event)
				}
			}
			if declined {
				if bob != nil && !hub.Connected(bob) {
					t.Fatal("background rejection disconnected existing app")
				}
				if _, err := hub.ActiveCall("bob"); err == nil {
					t.Fatal("call is still active")
				}
				page, err := db.CallHistory("bob", 0, 20)
				outcome := state.CallOutcomeRejected
				if scenario == "waiting" {
					outcome = state.CallOutcomeBusy
				}
				if err != nil || len(page.Items) != 1 || page.Items[0].Outcome != outcome || page.UnreadMissed != 0 {
					t.Fatalf("history = %+v, %v", page, err)
				}
				replay, err := hub.Resume("bob", callID, 0)
				if err != nil || len(replay) == 0 || replay[len(replay)-1].Type != "call.reject" {
					t.Fatalf("replay = %+v, %v", replay, err)
				}
				if again := reject(); again.Code != http.StatusNoContent {
					t.Fatalf("retry: %d", again.Code)
				}
				if event, ok := alice.TryNext(); ok {
					t.Fatalf("duplicate event: %+v", event)
				}
			}
			if scenario == "accepted" || scenario == "unknown" {
				if _, err := hub.ActiveCall("bob"); err != nil {
					t.Fatal("stale notification affected current call")
				}
			}
		})
	}
}
