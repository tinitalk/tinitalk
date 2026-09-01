package notify

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"

	webpushlib "github.com/ergochat/webpush-go/v2"
	"tinitalk/internal/webpush"
)

type HTTPWebPushSender struct {
	Client     webpushlib.HTTPClient
	VAPIDKeys  *webpushlib.VAPIDKeys
	Subscriber string
}

func (s HTTPWebPushSender) Send(request WebPushRequest) error {
	subscription, _, err := webpush.ParseSubscription([]byte(request.Subscription))
	if err != nil {
		return err
	}
	keys, err := webpushlib.DecodeSubscriptionKeys(subscription.Keys.Auth, subscription.Keys.P256DH)
	if err != nil {
		return err
	}
	payload, err := json.Marshal(request.Data)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), RequestTimeout)
	defer cancel()
	response, err := webpushlib.SendNotification(ctx, payload, &webpushlib.Subscription{
		Endpoint: subscription.Endpoint,
		Keys:     keys,
	}, &webpushlib.Options{
		HTTPClient: s.Client,
		Subscriber: s.Subscriber,
		TTL:        int(request.TTL.Seconds()),
		Urgency:    webpushlib.UrgencyHigh,
		VAPIDKeys:  s.VAPIDKeys,
	})
	if err != nil {
		var urlError *url.Error
		var networkError net.Error
		if errors.As(err, &urlError) || errors.As(err, &networkError) {
			return fmt.Errorf("%w: %v", ErrTemporaryPushDelivery, err)
		}
		return err
	}
	defer response.Body.Close()
	if response.StatusCode >= 200 && response.StatusCode <= 299 {
		return nil
	}
	if response.StatusCode == http.StatusNotFound || response.StatusCode == http.StatusGone {
		return ErrInvalidPushSubscription
	}
	if response.StatusCode == http.StatusRequestTimeout || response.StatusCode == http.StatusTooEarly ||
		response.StatusCode == http.StatusTooManyRequests ||
		(response.StatusCode >= http.StatusInternalServerError && response.StatusCode <= 599) {
		return fmt.Errorf("%w: HTTP %d", ErrTemporaryPushDelivery, response.StatusCode)
	}
	return fmt.Errorf("WebPush send failed: HTTP %d", response.StatusCode)
}
