package notify

import (
	"encoding/json"
	"net/url"
	"testing"

	"tinitalk/internal/webpush"
)

func TestAppleDeclarativeNotificationCarriesAccountNavigationAndCallData(t *testing.T) {
	subscription := webpush.Subscription{ClientType: "web", Endpoint: "https://web.push.apple.com/token", WebAppURL: "https://official.example/talk/#account=family-a"}
	data := map[string]string{"type": "incoming_call", "call_id": "call-1", "caller": "Мама", "caller_login": "alice", "target_session_id": "session", "expires_at": "2026-09-14T12:00:45Z"}
	raw, err := webPushPayload(subscription, data)
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		WebPush      int `json:"web_push"`
		Notification struct {
			Title, Body, Navigate, Tag string
			Silent                     *bool
			Data                       struct {
				AccountID string            `json:"accountId"`
				CallID    string            `json:"callId"`
				SessionID string            `json:"sessionId"`
				TiniTalk  map[string]string `json:"tinitalk"`
			}
		}
	}
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got.WebPush != 8030 || got.Notification.Title != "📞 Мама звонит" || got.Notification.Body == "" || got.Notification.Silent == nil || *got.Notification.Silent {
		t.Fatalf("missing visible fallback: %s", raw)
	}
	if got.Notification.Navigate != "https://official.example/talk/#account=family-a&call=call-1" || got.Notification.Tag != "family-a:call-1" {
		t.Fatalf("wrong installation/account destination: %s", raw)
	}
	if got.Notification.Data.AccountID != "family-a" || got.Notification.Data.CallID != "call-1" || got.Notification.Data.SessionID != "session" || got.Notification.Data.TiniTalk["expires_at"] != data["expires_at"] {
		t.Fatalf("worker cannot recover call/session: %s", raw)
	}
}

func TestDeclarativeNavigationCannotTurnCallIDIntoAnAction(t *testing.T) {
	subscription := webpush.Subscription{ClientType: "web", Endpoint: "https://web.push.apple.com/token", WebAppURL: "https://web.example/#account=a"}
	raw, err := webPushPayload(subscription, map[string]string{"type": "incoming_call", "call_id": "call&action=answer"})
	if err != nil {
		t.Fatal(err)
	}
	var got struct{ Notification struct{ Navigate string } }
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	navigate, err := url.Parse(got.Notification.Navigate)
	if err != nil {
		t.Fatal(err)
	}
	params, err := url.ParseQuery(navigate.Fragment)
	if err != nil || len(params) != 2 || params.Get("call") != "call&action=answer" || params.Has("action") {
		t.Fatalf("unsafe navigation: %s", raw)
	}
}

func TestLegacyAndNonApplePushPayloadsKeepTheirExistingFormat(t *testing.T) {
	for _, subscription := range []webpush.Subscription{
		{Endpoint: "https://web.push.apple.com/token", ClientType: "web"},
		{Endpoint: "https://fcm.googleapis.com/wp/token", ClientType: "web", WebAppURL: "https://web.example/#account=a"},
		{Endpoint: "https://fcm.distributor.unifiedpush.org/wpfcm?t=token"},
	} {
		raw, err := webPushPayload(subscription, map[string]string{"type": "incoming_call", "call_id": "call-1"})
		if err != nil {
			t.Fatal(err)
		}
		var got map[string]string
		if err := json.Unmarshal(raw, &got); err != nil || got["type"] != "incoming_call" || len(got) != 2 {
			t.Fatalf("legacy payload changed: %s, %v", raw, err)
		}
	}
}

func TestAppleSessionReplacementHasVisibleFallbackWithoutCallAction(t *testing.T) {
	subscription := webpush.Subscription{Endpoint: "https://web.push.apple.com/token", ClientType: "web", WebAppURL: "https://web.example/#account=a"}
	raw, err := webPushPayload(subscription, map[string]string{"type": "session_replaced", "revoked_session_id": "old"})
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		Notification struct{ Title, Navigate string }
	}
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got.Notification.Title == "" || got.Notification.Navigate != "https://web.example/#account=a" {
		t.Fatalf("session notification: %s", raw)
	}
}
