package httpapi

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestWebPushConfigReturnsServerVAPIDIdentity(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{
		AllowInsecureLoopback: true,
		WebPushPublicKey:      "public-vapid-key",
		WebPushConfigID:       "sha256:webpush",
	})

	response := request(t, server, http.MethodGet, "/api/webpush-config", nil, "alice", tokens["alice"])
	if response.Code != http.StatusOK {
		t.Fatalf("WebPush config status = %d, body %s", response.Code, response.Body.String())
	}
	var got map[string]string
	if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got["vapid_public_key"] != "public-vapid-key" || got["config_id"] != "sha256:webpush" {
		t.Fatalf("WebPush config = %#v", got)
	}
}
