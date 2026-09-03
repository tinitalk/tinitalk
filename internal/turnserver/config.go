package turnserver

import (
	"encoding/json"
	"strconv"
	"time"
)

type ICEConfigProvider struct {
	PublicHost  string
	TURNPort    uint16
	TURNTLSPort uint16
	Realm       string
	Issuer      CredentialIssuer
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
	turnPort := p.TURNPort
	if turnPort == 0 {
		turnPort = 3478
	}
	turnTLSPort := p.TURNTLSPort
	if turnTLSPort == 0 {
		turnTLSPort = 5349
	}
	turnEndpoint := p.PublicHost + ":" + strconv.Itoa(int(turnPort))
	turnTLSEndpoint := p.PublicHost + ":" + strconv.Itoa(int(turnTLSPort))
	urls := []string{
		"stun:" + turnEndpoint,
		"turn:" + turnEndpoint + "?transport=udp",
		"turn:" + turnEndpoint + "?transport=tcp",
		"turns:" + turnTLSEndpoint + "?transport=tcp",
	}
	return urls
}
