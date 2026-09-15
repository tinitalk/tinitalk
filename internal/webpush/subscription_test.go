package webpush

import (
	"encoding/json"
	"testing"
)

func TestBrowserNotificationURLValidationAndPersistence(t *testing.T) {
	for _, tc := range []struct {
		name, client, appURL string
		valid                bool
	}{
		{"official host", "web", "https://official.example/#account=family-a", true},
		{"subdirectory", "web", "https://official.example/talk/#account=family-b", true},
		{"legacy", "", "", true},
		{"native", "", "https://official.example/#account=family-a", false},
		{"insecure", "web", "http://official.example/#account=family-a", false},
		{"credentials", "web", "https://user:password@official.example/#account=family-a", false},
		{"action injection", "web", "https://official.example/#account=family-a&action=answer", false},
		{"duplicate account", "web", "https://official.example/#account=a&account=b", false},
		{"no account", "web", "https://official.example/", false},
		{"invalid account", "web", "https://official.example/#account=..%2Fother", false},
		{"query", "web", "https://official.example/?token=secret#account=a", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			raw, _ := json.Marshal(map[string]any{
				"endpoint": "https://web.push.apple.com/token", "client_type": tc.client, "web_app_url": tc.appURL,
				"keys": map[string]string{"p256dh": "BEkDdNnpEcD8M4mRGOFJWTDJ4GkDI5Xs3vpIOrAaBZKRCVv6V3sB3CFujTFiD6DHda7W8pCyChJDU205otrbCAw", "auth": "AAAAAAAAAAAAAAAAAAAAAA"},
			})
			_, canonical, err := ParseSubscription(raw)
			if (err == nil) != tc.valid {
				t.Fatalf("valid=%v, error=%v", tc.valid, err)
			}
			if err != nil {
				return
			}
			var stored map[string]any
			if err := json.Unmarshal([]byte(canonical), &stored); err != nil {
				t.Fatal(err)
			}
			if tc.appURL != "" && stored["web_app_url"] != tc.appURL {
				t.Fatalf("navigation metadata lost: %s", canonical)
			}
		})
	}
}

func TestBrowserProviderEndpoints(t *testing.T) {
	for _, endpoint := range []string{"https://web.push.apple.com/token", "https://updates.push.services.mozilla.com/wpush/v2/token", "https://fcm.googleapis.com/wp/token"} {
		if err := validateEndpoint(endpoint); err != nil {
			t.Fatalf("%s: %v", endpoint, err)
		}
	}
	for _, endpoint := range []string{"https://push.apple.com.attacker.org/token", "https://web.push.apple.com:444/token", "https://web.push.apple.com@127.0.0.1/token", "http://web.push.apple.com/token", "https://127.0.0.1/token", "https://web.push.apple.com/"} {
		if validateEndpoint(endpoint) == nil {
			t.Fatalf("unsafe endpoint accepted: %s", endpoint)
		}
	}
}

func TestEmbeddedDistributorEndpointValidation(t *testing.T) {
	if err := validateEndpoint("https://fcm.distributor.unifiedpush.org/wpfcm?t=token"); err != nil {
		t.Fatalf("embedded distributor endpoint rejected: %v", err)
	}
	for _, endpoint := range []string{
		"https://attacker.example/wpfcm?t=token",
		"https://fcm.distributor.unifiedpush.org/other?t=token",
		"https://fcm.distributor.unifiedpush.org/wpfcm?t=token&url=https://attacker.example",
	} {
		if err := validateEndpoint(endpoint); err == nil {
			t.Fatalf("unsafe endpoint accepted: %s", endpoint)
		}
	}
}
