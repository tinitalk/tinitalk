package turnserver

import (
	"encoding/json"
	"slices"
	"testing"
	"time"
)

func TestTemporaryCredentialsUseHMACAndExpire(t *testing.T) {
	issuer := CredentialIssuer{Secret: []byte("secret"), TTL: time.Minute, Now: func() time.Time { return time.Unix(1000, 0) }}

	cred := issuer.Issue("alice")

	if cred.Username != "1060:alice" {
		t.Fatalf("Username = %q", cred.Username)
	}
	if cred.Password == "" {
		t.Fatal("Password is empty")
	}
	if !issuer.Valid(cred.Username, cred.Password) {
		t.Fatal("issued credential is not valid")
	}
	if issuer.Valid(cred.Username, "wrong") {
		t.Fatal("wrong password accepted")
	}
}

func TestCredentialLoginIsStableAndRejectsInvalidUsernames(t *testing.T) {
	issuer := CredentialIssuer{Now: func() time.Time { return time.Unix(1_000, 0) }}

	for _, username := range []string{"1060:alice", "1120:alice"} {
		login, ok := issuer.Login(username)
		if !ok || login != "alice" {
			t.Fatalf("Login(%q) = %q, %v", username, login, ok)
		}
	}
	for _, username := range []string{"", "1060", "invalid:alice", "999:alice", "1060:"} {
		if login, ok := issuer.Login(username); ok {
			t.Fatalf("Login(%q) = %q, true", username, login)
		}
	}
}

func TestICEConfigPayloadContainsTemporaryTurnCredentials(t *testing.T) {
	issuer := CredentialIssuer{
		Secret: []byte("secret"),
		TTL:    10 * time.Minute,
		Now:    func() time.Time { return time.Date(2026, time.August, 26, 10, 0, 0, 0, time.UTC) },
	}
	provider := ICEConfigProvider{
		PublicHost: "calls.example.com",
		Realm:      "calls.example.com",
		Issuer:     issuer,
	}

	raw := provider.ICEConfig("call-1", "alice")

	var payload struct {
		Servers []struct {
			URLs       []string `json:"urls"`
			Username   string   `json:"username"`
			Credential string   `json:"credential"`
			ExpiresAt  string   `json:"expires_at"`
		} `json:"ice_servers"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Servers) != 1 {
		t.Fatalf("servers = %+v", payload.Servers)
	}
	got := payload.Servers[0]
	wantURLs := []string{
		"stun:calls.example.com:3478",
		"turn:calls.example.com:3478?transport=udp",
		"turn:calls.example.com:3478?transport=tcp",
		"turns:calls.example.com:5349?transport=tcp",
	}
	if !slices.Equal(got.URLs, wantURLs) {
		t.Fatalf("urls = %+v", got.URLs)
	}
	if got.Username != "1787739000:alice" || got.Credential == "" {
		t.Fatalf("credential = %+v", got)
	}
	if got.ExpiresAt != "2026-08-26T10:10:00Z" {
		t.Fatalf("expires_at = %q", got.ExpiresAt)
	}
}
