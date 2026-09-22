package notify

import (
	"encoding/json"
	"net/url"
	"testing"

	"tinitalk/internal/webpush"
)

func TestFallbackLanguages(t *testing.T) {
	for _, language := range []string{"en", "ru", "pl", "de", "es", "fr", "pt", "it", "tr", "ja", "ko", "zh-Hans", "", "unknown"} {
		for _, event := range []string{"incoming_call", "session_replaced"} {
			t.Run(language+"/"+event, func(t *testing.T) {
				subscription := webpush.Subscription{ClientType: "web", Endpoint: "https://web.push.apple.com/token", WebAppURL: "https://web.example/#account=a", Language: language}
				raw, err := webPushPayload(subscription, map[string]string{"type": event, "caller": "Alice", "call_id": "c"})
				if err != nil {
					t.Fatal(err)
				}
				var got struct {
					Notification struct{ Title, Body, Lang string }
				}
				if err := json.Unmarshal(raw, &got); err != nil {
					t.Fatal(err)
				}
				want, messages := fallbackMessages(language)
				if got.Notification.Lang != want || got.Notification.Body == "" || got.Notification.Title == "" {
					t.Fatalf("invalid fallback: %s", raw)
				}
				if event == "session_replaced" && got.Notification.Title != messages.Session {
					t.Fatalf("wrong session title: %s", raw)
				}
				if (language == "" || language == "unknown") && got.Notification.Lang != "en" {
					t.Fatal("missing English fallback")
				}
			})
		}
	}
	if len(fallbackTranslations) != 12 {
		t.Fatal("expected 12 languages")
	}
}

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
	if got.WebPush != 8030 || got.Notification.Title != "📞 Мама is calling" || got.Notification.Body == "" || got.Notification.Silent == nil || *got.Notification.Silent {
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
