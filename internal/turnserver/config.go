package turnserver

import "encoding/json"

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
		} `json:"ice_servers"`
	}{
		Servers: []struct {
			URLs       []string `json:"urls"`
			Username   string   `json:"username"`
			Credential string   `json:"credential"`
		}{
			{
				URLs:       p.urls(),
				Username:   cred.Username,
				Credential: cred.Password,
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
		"turns:" + p.PublicHost + ":5349?transport=tcp",
	}
	return urls
}
