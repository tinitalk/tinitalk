package notify

import (
	"encoding/json"
	"net/url"
	"strings"

	"tinitalk/internal/webpush"
)

// The fallback contains enough information to display and navigate without
// worker execution or local storage. Only updated Apple browser registrations
// advertise WebAppURL; native and older browser payloads keep their wire format.
func webPushPayload(subscription webpush.Subscription, data map[string]string) ([]byte, error) {
	endpoint, err := url.Parse(subscription.Endpoint)
	if err != nil || !strings.HasSuffix(endpoint.Hostname(), ".push.apple.com") || subscription.ClientType != "web" || subscription.WebAppURL == "" {
		return json.Marshal(data)
	}
	if data["type"] != "incoming_call" && data["type"] != "session_replaced" {
		return json.Marshal(data)
	}
	navigate, err := url.Parse(subscription.WebAppURL)
	if err != nil {
		return nil, err
	}
	params, err := url.ParseQuery(navigate.Fragment)
	if err != nil {
		return nil, err
	}
	accountID := params.Get("account")
	callID := data["call_id"]
	title := "Выполнен вход на другом устройстве"
	body := "Откройте TiniTalk, чтобы проверить аккаунт"
	tag := accountID + ":session"
	if data["type"] == "incoming_call" {
		caller := data["caller"]
		if caller == "" {
			caller = data["caller_login"]
		}
		title = "📞 Входящий звонок"
		if caller != "" {
			title = "📞 " + caller + " звонит"
		}
		body = "Нажмите, чтобы открыть входящий звонок"
		params.Set("call", callID)
		tag = accountID + ":" + callID
	}
	navigate.Fragment = params.Encode()
	return json.Marshal(map[string]any{
		"web_push": 8030,
		"notification": map[string]any{
			"title": title, "body": body, "navigate": navigate.String(),
			"tag": tag, "lang": "ru", "silent": false,
			"data": map[string]any{
				"accountId": accountID, "callId": callID, "sessionId": data["target_session_id"],
				"tinitalk": data,
			},
		},
	})
}
