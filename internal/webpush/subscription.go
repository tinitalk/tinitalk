package webpush

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/url"
	"regexp"
	"strings"

	webpushlib "github.com/ergochat/webpush-go/v2"
)

type Subscription struct {
	Language   string `json:"language,omitempty"`
	ClientType string `json:"client_type,omitempty"`
	WebAppURL  string `json:"web_app_url,omitempty"`
	Endpoint   string `json:"endpoint"`
	Keys       Keys   `json:"keys"`
}

type Keys struct {
	P256DH string `json:"p256dh"`
	Auth   string `json:"auth"`
}

func ParseSubscription(raw []byte) (Subscription, string, error) {
	var subscription Subscription
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&subscription); err != nil {
		return Subscription{}, "", err
	}
	if subscription.ClientType != "" && subscription.ClientType != "web" {
		return Subscription{}, "", errors.New("unsupported push client")
	}
	if len(subscription.Language) > 35 {
		return Subscription{}, "", errors.New("WebPush language is too long")
	}
	if err := validateEndpoint(subscription.Endpoint); err != nil {
		return Subscription{}, "", err
	}
	if subscription.WebAppURL != "" {
		if subscription.ClientType != "web" || !validWebAppURL(subscription.WebAppURL) {
			return Subscription{}, "", errors.New("invalid WebPush web app URL")
		}
	}
	if len(subscription.Keys.Auth) > 64 || len(subscription.Keys.P256DH) > 256 {
		return Subscription{}, "", errors.New("WebPush subscription keys are too long")
	}
	if _, err := webpushlib.DecodeSubscriptionKeys(subscription.Keys.Auth, subscription.Keys.P256DH); err != nil {
		return Subscription{}, "", err
	}
	canonical, err := json.Marshal(subscription)
	if err != nil {
		return Subscription{}, "", err
	}
	return subscription, string(canonical), nil
}

func validateEndpoint(endpoint string) error {
	if len(endpoint) == 0 || len(endpoint) > 2048 {
		return errors.New("invalid WebPush endpoint")
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Scheme != "https" || parsed.User != nil || parsed.Fragment != "" {
		return errors.New("unsupported WebPush endpoint")
	}
	if parsed.Host == "fcm.googleapis.com" && parsed.RawQuery == "" {
		const prefix = "/fcm/send/"
		if strings.HasPrefix(parsed.EscapedPath(), prefix) && len(parsed.EscapedPath()) > len(prefix) {
			return nil
		}
	}
	if parsed.Port() == "" && parsed.Path != "" && parsed.Path != "/" {
		host := parsed.Hostname()
		if strings.HasSuffix(host, ".push.apple.com") || host == "updates.push.services.mozilla.com" {
			return nil
		}
		if host == "fcm.googleapis.com" && strings.HasPrefix(parsed.EscapedPath(), "/wp/") {
			return nil
		}
	}
	if parsed.Host == "fcm.distributor.unifiedpush.org" && parsed.EscapedPath() == "/wpfcm" {
		query := parsed.Query()
		if len(query) == 1 && len(query["t"]) == 1 && query.Get("t") != "" {
			return nil
		}
	}
	return errors.New("unsupported WebPush endpoint")
}

func IsBrowserSubscription(raw string) bool {
	var subscription Subscription
	return json.Unmarshal([]byte(raw), &subscription) == nil && subscription.ClientType == "web"
}

// Apple Web Push requires a visible notification for every delivered push,
// including subscriptions created before client_type was added.
func IsAppleSubscription(raw string) bool {
	var subscription Subscription
	if json.Unmarshal([]byte(raw), &subscription) != nil {
		return false
	}
	endpoint, err := url.Parse(subscription.Endpoint)
	return err == nil && endpoint.Scheme == "https" && strings.HasSuffix(endpoint.Hostname(), ".push.apple.com")
}

var browserAccountID = regexp.MustCompile(`^[a-zA-Z0-9-]{1,128}$`)

func validWebAppURL(value string) bool {
	if len(value) > 2048 {
		return false
	}
	appURL, err := url.Parse(value)
	if err != nil || appURL.Scheme != "https" || appURL.Hostname() == "" || appURL.User != nil || appURL.RawQuery != "" || appURL.ForceQuery {
		return false
	}
	fragment, err := url.ParseQuery(appURL.Fragment)
	return err == nil && len(fragment) == 1 && len(fragment["account"]) == 1 && browserAccountID.MatchString(fragment.Get("account"))
}
