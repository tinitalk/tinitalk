package notify

import (
	"bytes"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
	"testing"
	"time"

	webpushlib "github.com/ergochat/webpush-go/v2"
	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func TestHTTPWebPushSenderUsesUrgentDeliveryAndInvalidatesGoneEndpoint(t *testing.T) {
	keys, err := webpushlib.GenerateVAPIDKeys()
	if err != nil {
		t.Fatal(err)
	}
	client := &captureWebPushClient{status: http.StatusCreated}
	sender := HTTPWebPushSender{Client: client, VAPIDKeys: keys, Subscriber: "https://talk.example"}
	request := WebPushRequest{
		Subscription: notifySubscription("account"),
		Data:         map[string]string{"type": "incoming_call", "call_id": "call-1"},
		TTL:          30 * time.Second,
	}
	if err := sender.Send(request); err != nil {
		t.Fatal(err)
	}
	if client.request == nil || client.request.URL.String() != "https://fcm.distributor.unifiedpush.org/wpfcm?t=account" {
		t.Fatalf("WebPush request URL = %+v", client.request)
	}
	if client.request.Header.Get("Urgency") != "high" || client.request.Header.Get("TTL") != "30" || client.request.Header.Get("Content-Encoding") != "aes128gcm" {
		t.Fatalf("WebPush headers = %+v", client.request.Header)
	}
	client.status = http.StatusGone
	if err := sender.Send(request); !errors.Is(err, ErrInvalidPushSubscription) {
		t.Fatalf("gone endpoint error = %v, want invalid subscription", err)
	}
	client.status = http.StatusServiceUnavailable
	if err := sender.Send(request); !errors.Is(err, ErrTemporaryPushDelivery) {
		t.Fatalf("unavailable endpoint error = %v, want temporary delivery failure", err)
	}
}

func TestHTTPWebPushSenderDoesNotRetryPermanentLocalOrHTTPFailures(t *testing.T) {
	keys, err := webpushlib.GenerateVAPIDKeys()
	if err != nil {
		t.Fatal(err)
	}
	client := &captureWebPushClient{status: 600}
	sender := HTTPWebPushSender{Client: client, VAPIDKeys: keys, Subscriber: "https://talk.example"}
	request := WebPushRequest{
		Subscription: notifySubscription("account"),
		Data:         map[string]string{"type": "incoming_call", "call_id": "call-1"},
		TTL:          30 * time.Second,
	}
	if err := sender.Send(request); err == nil || errors.Is(err, ErrTemporaryPushDelivery) {
		t.Fatalf("HTTP 600 error = %v, want permanent delivery failure", err)
	}
	request.Data["oversized"] = strings.Repeat("x", 5000)
	if err := sender.Send(request); err == nil || errors.Is(err, ErrTemporaryPushDelivery) {
		t.Fatalf("oversized payload error = %v, want permanent local failure", err)
	}
}

func TestNotifierRetriesTemporaryWebPushFailure(t *testing.T) {
	sender := &fakeWebPushSender{errs: []error{
		fmt.Errorf("first attempt: %w", ErrTemporaryPushDelivery),
		nil,
	}}
	notifier := NewPushNotifier(
		&fakePushTargetStore{targets: []state.Device{{PushTarget: notifyTarget("phone")}}},
		sender,
	)
	notifier.retryDelays = []time.Duration{0}

	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})

	if sender.calls != 2 {
		t.Fatalf("send calls = %d, want initial attempt and one retry", sender.calls)
	}
}

func TestNotifierReportsExhaustedTemporaryWebPushFailure(t *testing.T) {
	var output bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&output)
	t.Cleanup(func() { log.SetOutput(previous) })
	sender := &fakeWebPushSender{err: fmt.Errorf("provider unavailable: %w", ErrTemporaryPushDelivery)}
	notifier := NewPushNotifier(
		&fakePushTargetStore{targets: []state.Device{{PushTarget: notifyTarget("phone")}}},
		sender,
	)
	notifier.retryDelays = []time.Duration{0}

	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})

	if !strings.Contains(output.String(), "WebPush delivery failed after retries") {
		t.Fatalf("log output = %q, want exhausted delivery failure", output.String())
	}
}

func TestWakeMessageFormatsCallDataAndExpiryInUTC(t *testing.T) {
	originalLocal := time.Local
	time.Local = time.FixedZone("server-local", 3*60*60)
	t.Cleanup(func() { time.Local = originalLocal })
	message := WakeMessage(signaling.DeliveredEvent{
		Event: protocol.Event{CallID: "call-1", Type: "call.incoming", SentAt: 1000},
		Seq:   7,
	}, "alice", "Alice", 30*time.Second)
	if message.Data["type"] != "incoming_call" || message.Data["caller_login"] != "alice" || message.Data["last_seq"] != "7" {
		t.Fatalf("wake data = %+v", message.Data)
	}
	if message.Data["expires_at"] != "1970-01-01T00:00:31Z" || message.ttl != 30*time.Second {
		t.Fatalf("wake expiry/TTL = %q/%s", message.Data["expires_at"], message.ttl)
	}
}

func TestNotifierDisablesInvalidWebPushSubscription(t *testing.T) {
	target := notifyTarget("bad")
	store := &fakePushTargetStore{targets: []state.Device{{DeviceID: "phone", PushTarget: target}}, displayName: "Мама"}
	sender := &fakeWebPushSender{err: ErrInvalidPushSubscription}
	notifier := NewPushNotifier(store, sender)
	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})
	if sender.calls != 1 || store.disabled != target {
		t.Fatalf("send calls = %d, disabled = %+v", sender.calls, store.disabled)
	}
	if sender.last.Data["caller"] != "Мама" || sender.last.Data["caller_login"] != "alice" {
		t.Fatalf("call data = %+v", sender.last.Data)
	}
}

func TestNotifierUsesLongTTLForUnansweredCallUpdates(t *testing.T) {
	for _, eventType := range []string{"call.cancel", "call.expire", "call.busy"} {
		t.Run(eventType, func(t *testing.T) {
			sender := &fakeWebPushSender{}
			notifier := NewPushNotifier(&fakePushTargetStore{targets: []state.Device{{PushTarget: notifyTarget(eventType)}}}, sender)
			notifier.CancelCall("bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1", Type: eventType}})
			if sender.last.TTL != 28*24*time.Hour || sender.last.Data["call_event"] != eventType {
				t.Fatalf("cancel request = %+v", sender.last)
			}
		})
	}
}

func TestNotifierTargetsOnlySnapshottedManagedDevice(t *testing.T) {
	sender := &fakeWebPushSender{}
	phone := notifyTarget("phone")
	store := &fakePushTargetStore{targets: []state.Device{
		{DeviceID: "phone", PushTarget: phone},
		{DeviceID: "tablet", PushTarget: notifyTarget("tablet")},
	}}
	notifier := NewPushNotifier(store, sender)
	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{
		Event:              protocol.Event{CallID: "call-1"},
		TargetSessionKnown: true,
		TargetSessionID:    "bob-session",
		TargetDeviceID:     "phone",
	})
	if len(sender.requests) != 1 || sender.requests[0].Subscription != phone.Subscription {
		t.Fatalf("targeted requests = %+v, want only phone", sender.requests)
	}
	data := sender.requests[0].Data
	if data["target_login"] != "bob" || data["target_device_id"] != "phone" || data["target_session_id"] != "bob-session" {
		t.Fatalf("target data = %+v", data)
	}
}

func TestNotifierSendsSessionReplacementToRevokedSubscription(t *testing.T) {
	sender := &fakeWebPushSender{}
	target := notifyTarget("old-phone")
	notifier := NewPushNotifier(&fakePushTargetStore{}, sender)
	notifier.SessionReplaced("alice", "old-session", []state.Device{{DeviceID: "old-phone", PushTarget: target}, {DeviceID: "unused"}})
	if sender.calls != 1 || sender.last.Subscription != target.Subscription || sender.last.TTL != 28*24*time.Hour {
		t.Fatalf("session replacement request = %+v", sender.last)
	}
	if sender.last.Data["revoked_session_id"] != "old-session" || sender.last.Data["revoked_device_id"] != "old-phone" {
		t.Fatalf("session replacement data = %+v", sender.last.Data)
	}
}

func TestNotifierSuppressesCallAfterTargetResolutionFailure(t *testing.T) {
	sender := &fakeWebPushSender{}
	notifier := NewPushNotifier(&fakePushTargetStore{targets: []state.Device{{PushTarget: notifyTarget("phone")}}}, sender)
	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{
		Event:                  protocol.Event{CallID: "call-1"},
		TargetResolutionFailed: true,
	})
	if sender.calls != 0 {
		t.Fatalf("send calls = %d, want fail-closed suppression", sender.calls)
	}
}

type fakePushTargetStore struct {
	targets     []state.Device
	disabled    state.PushTarget
	displayName string
}

func (s *fakePushTargetStore) PushTargetsForUser(string) ([]state.Device, error) {
	return s.targets, nil
}
func (s *fakePushTargetStore) ContactDisplayName(string, string) (string, error) {
	return s.displayName, nil
}
func (s *fakePushTargetStore) DisablePushTarget(target state.PushTarget) error {
	s.disabled = target
	return nil
}

type fakeWebPushSender struct {
	calls    int
	err      error
	errs     []error
	last     WebPushRequest
	requests []WebPushRequest
}

func (s *fakeWebPushSender) Send(request WebPushRequest) error {
	s.calls++
	s.last = request
	s.requests = append(s.requests, request)
	if len(s.errs) > 0 {
		err := s.errs[0]
		s.errs = s.errs[1:]
		return err
	}
	return s.err
}

type captureWebPushClient struct {
	request *http.Request
	status  int
}

func (c *captureWebPushClient) Do(request *http.Request) (*http.Response, error) {
	c.request = request
	return &http.Response{StatusCode: c.status, Body: http.NoBody}, nil
}

func notifyTarget(id string) state.PushTarget {
	return state.PushTarget{Subscription: notifySubscription(id), ConfigID: "sha256:webpush"}
}

func notifySubscription(id string) string {
	return `{"endpoint":"https://fcm.distributor.unifiedpush.org/wpfcm?t=` + id + `","keys":{"p256dh":"BEkDdNnpEcD8M4mRGOFJWTDJ4GkDI5Xs3vpIOrAaBZKRCVv6V3sB3CFujTFiD6DHda7W8pCyChJDU205otrbCAw","auth":"AAAAAAAAAAAAAAAAAAAAAA"}}`
}
