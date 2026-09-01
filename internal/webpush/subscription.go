package webpush

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/url"
	"strings"

	webpushlib "github.com/ergochat/webpush-go/v2"
)

type Subscription struct {
	Endpoint string `json:"endpoint"`
	Keys     Keys   `json:"keys"`
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
	if err := validateEndpoint(subscription.Endpoint); err != nil {
		return Subscription{}, "", err
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
	if parsed.Host == "fcm.distributor.unifiedpush.org" && parsed.EscapedPath() == "/wpfcm" {
		query := parsed.Query()
		if len(query) == 1 && len(query["t"]) == 1 && query.Get("t") != "" {
			return nil
		}
	}
	return errors.New("unsupported WebPush endpoint")
}
