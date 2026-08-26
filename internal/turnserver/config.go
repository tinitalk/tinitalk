package turnserver

import (
	"encoding/json"
	"time"
)

type ICEConfigProvider struct {
	PublicHost string
	Realm      string
	Issuer     CredentialIssuer
}

func (p ICEConfigProvider) ICEConfig(_ string, user string) json.RawMessage {
	cred := p.Issuer.Issue(user)
	payload := struct {
		Servers []struct {
			URLs       []string `json:"urls"`
			Username   string   `json:"username"`
			Credential string   `json:"credential"`
			ExpiresAt  string   `json:"expires_at"`
		} `json:"ice_servers"`
	}{
		Servers: []struct {
			URLs       []string `json:"urls"`
			Username   string   `json:"username"`
			Credential string   `json:"credential"`
			ExpiresAt  string   `json:"expires_at"`
		}{
			{
				URLs:       p.urls(),
				Username:   cred.Username,
				Credential: cred.Password,
				ExpiresAt:  cred.Expires.UTC().Format(time.RFC3339),
			},
		},
	}
	raw, _ := json.Marshal(payload)
	return raw
}

func (p ICEConfigProvider) urls() []string {
	urls := []string{
		"stun:" + p.PublicHost + ":3478",
		"turn:" + p.PublicHost + ":3478?transport=udp",
		"turn:" + p.PublicHost + ":3478?transport=tcp",
		"turns:" + p.PublicHost + ":5349?transport=tcp",
	}
	return urls
}
