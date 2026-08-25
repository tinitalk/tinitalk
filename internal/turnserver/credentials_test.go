package turnserver

import (
	"encoding/json"
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

func TestICEConfigPayloadContainsTemporaryTurnCredentials(t *testing.T) {
	issuer := CredentialIssuer{Secret: []byte("secret"), TTL: time.Minute, Now: func() time.Time { return time.Unix(1000, 0) }}
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
		} `json:"ice_servers"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Servers) != 1 {
		t.Fatalf("servers = %+v", payload.Servers)
	}
	got := payload.Servers[0]
	if got.URLs[0] != "stun:calls.example.com:3478" || got.URLs[1] != "turn:calls.example.com:3478?transport=udp" || got.URLs[2] != "turns:calls.example.com:5349?transport=tcp" {
		t.Fatalf("urls = %+v", got.URLs)
	}
	if got.Username != "1060:alice" || got.Credential == "" {
		t.Fatalf("credential = %+v", got)
	}
}
