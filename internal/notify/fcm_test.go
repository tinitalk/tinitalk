package notify

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	webpushlib "github.com/ergochat/webpush-go/v2"
	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func TestWakeMessageUsesHighPriorityAndShortTTL(t *testing.T) {
	msg := WakeMessage(state.PushTarget{Kind: state.KindToken, Value: "token-1"}, signaling.DeliveredEvent{
		Event: protocol.Event{CallID: "call-1", Type: "call.incoming", SentAt: 1000, Payload: json.RawMessage(`{"caller":"Alice"}`)},
		Seq:   1,
	}, "alice", "Alice", 30*time.Second)

	if msg.Message.Token != "token-1" {
		t.Fatalf("token = %q", msg.Message.Token)
	}
	if msg.Message.Android.Priority != "HIGH" || msg.Message.Android.TTL != "30s" {
		t.Fatalf("android = %+v", msg.Message.Android)
	}
	if msg.Message.Data["call_id"] != "call-1" || msg.Message.Data["type"] != "incoming_call" {
		t.Fatalf("data = %+v", msg.Message.Data)
	}
	if msg.Message.Data["last_seq"] != "1" {
		t.Fatalf("last_seq = %q", msg.Message.Data["last_seq"])
	}
	if msg.Message.Data["caller_login"] != "alice" {
		t.Fatalf("caller_login = %q", msg.Message.Data["caller_login"])
	}
}

func TestNotifierRoutesLegacyFCMAndWebPush(t *testing.T) {
	webPushTarget := state.PushTarget{
		Kind:     state.KindWebPush,
		Value:    `{"endpoint":"https://fcm.googleapis.com/fcm/send/account","keys":{"p256dh":"BEkDdNnpEcD8M4mRGOFJWTDJ4GkDI5Xs3vpIOrAaBZKRCVv6V3sB3CFujTFiD6DHda7W8pCyChJDU205otrbCAw","auth":"AAAAAAAAAAAAAAAAAAAAAA"}}`,
		ConfigID: "sha256:webpush",
	}
	fcmSender := &fakeSender{}
	webPushSender := &fakeWebPushSender{}
	notifier := NewPushNotifier(&fakePushTargetStore{targets: []state.Device{
		tokenDevice("old-phone", "legacy-token"),
		{DeviceID: "new-phone", PushTarget: webPushTarget},
	}}, fcmSender, webPushSender)

	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1", SentAt: 1000}})

	if fcmSender.calls != 1 || fcmSender.last.Message.Token != "legacy-token" {
		t.Fatalf("legacy FCM sends = %d, target %q", fcmSender.calls, fcmSender.last.Message.Token)
	}
	if webPushSender.calls != 1 || webPushSender.last.Subscription != webPushTarget.Value {
		t.Fatalf("WebPush sends = %d, subscription %q", webPushSender.calls, webPushSender.last.Subscription)
	}
	if webPushSender.last.Data["call_id"] != "call-1" || webPushSender.last.TTL != 30*time.Second {
		t.Fatalf("WebPush request = %+v", webPushSender.last)
	}
}

func TestHTTPWebPushSenderUsesUrgentShortLivedDeliveryAndInvalidatesGoneEndpoint(t *testing.T) {
	keys, err := webpushlib.GenerateVAPIDKeys()
	if err != nil {
		t.Fatal(err)
	}
	client := &captureWebPushClient{status: http.StatusCreated}
	sender := HTTPWebPushSender{
		Client:     client,
		VAPIDKeys:  keys,
		Subscriber: "https://talk.example",
	}
	request := WebPushRequest{
		Subscription: `{"endpoint":"https://fcm.distributor.unifiedpush.org/wpfcm?t=account","keys":{"p256dh":"BEkDdNnpEcD8M4mRGOFJWTDJ4GkDI5Xs3vpIOrAaBZKRCVv6V3sB3CFujTFiD6DHda7W8pCyChJDU205otrbCAw","auth":"AAAAAAAAAAAAAAAAAAAAAA"}}`,
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
	if err := sender.Send(request); !errors.Is(err, ErrInvalidRegistration) {
		t.Fatalf("gone endpoint error = %v, want invalid registration", err)
	}
}

func TestWakeMessageFormatsExpiryInUTC(t *testing.T) {
	originalLocal := time.Local
	time.Local = time.FixedZone("server-local", 3*60*60)
	t.Cleanup(func() { time.Local = originalLocal })

	msg := WakeMessage(state.PushTarget{Kind: state.KindToken, Value: "token-1"}, signaling.DeliveredEvent{
		Event: protocol.Event{CallID: "call-1", Type: "call.incoming", SentAt: 1000},
	}, "alice", "Alice", 30*time.Second)

	if got := msg.Message.Data["expires_at"]; got != "1970-01-01T00:00:31Z" {
		t.Fatalf("expires_at = %q, want canonical UTC time", got)
	}
}

func TestNotifierKeepsCallWhenSendFailsAndDisablesInvalidToken(t *testing.T) {
	badTarget := state.PushTarget{Kind: state.KindToken, Value: "bad-token"}
	store := &fakePushTargetStore{targets: []state.Device{tokenDevice("", "bad-token")}, displayName: "Мама"}
	sender := &fakeSender{err: ErrInvalidRegistration}
	notifier := NewPushNotifier(store, sender, nil)

	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})

	if sender.calls != 1 {
		t.Fatalf("send calls = %d", sender.calls)
	}
	if store.disabled != badTarget {
		t.Fatalf("disabled = %+v", store.disabled)
	}
	if sender.last.Message.Data["caller"] != "Мама" {
		t.Fatalf("caller = %q", sender.last.Message.Data["caller"])
	}
	if store.nameOwner != "bob" || store.nameContact != "alice" {
		t.Fatalf("name lookup = owner %q, contact %q", store.nameOwner, store.nameContact)
	}
	if sender.last.Message.Data["caller_login"] != "alice" {
		t.Fatalf("caller_login = %q", sender.last.Message.Data["caller_login"])
	}
}

func TestNotifierSendsCallCancellation(t *testing.T) {
	for _, eventType := range []string{"call.cancel", "call.expire"} {
		t.Run(eventType, func(t *testing.T) {
			store := &fakePushTargetStore{targets: []state.Device{tokenDevice("", "token-1")}}
			sender := &fakeSender{}
			notifier := NewPushNotifier(store, sender, nil)

			notifier.CancelCall("bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1", Type: eventType}})

			if sender.last.Message.Data["type"] != "call_cancel" || sender.last.Message.Data["call_id"] != "call-1" {
				t.Fatalf("data = %+v", sender.last.Message.Data)
			}
			if sender.last.Message.Android.TTL != "2419200s" {
				t.Fatalf("missed call TTL = %q, want 2419200s", sender.last.Message.Android.TTL)
			}
		})
	}
}

func TestNotifierKeepsBusyHistoryRefreshLongLived(t *testing.T) {
	store := &fakePushTargetStore{targets: []state.Device{tokenDevice("", "token-1")}}
	sender := &fakeSender{}
	notifier := NewPushNotifier(store, sender, nil)

	notifier.CancelCall("bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1", Type: "call.busy"}})

	if sender.last.Message.Android.TTL != "2419200s" {
		t.Fatalf("busy history refresh TTL = %q, want 2419200s", sender.last.Message.Android.TTL)
	}
}

func TestNotifierKeepsHandledCallCancellationShortLived(t *testing.T) {
	for _, eventType := range []string{"call.accept", "call.reject"} {
		t.Run(eventType, func(t *testing.T) {
			store := &fakePushTargetStore{targets: []state.Device{tokenDevice("", "token-1")}}
			sender := &fakeSender{}
			notifier := NewPushNotifier(store, sender, nil)

			notifier.CancelCall("bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1", Type: eventType}})

			if sender.last.Message.Android.TTL != "30s" {
				t.Fatalf("handled call TTL = %q, want 30s", sender.last.Message.Android.TTL)
			}
		})
	}
}

func TestCancelMessageIncludesSignalingEventType(t *testing.T) {
	msg := CancelMessage(state.PushTarget{Kind: state.KindToken, Value: "token"}, signaling.DeliveredEvent{
		Event: protocol.Event{CallID: "call-1", Type: "call.accept"},
	}, 30*time.Second)
	if got := msg.Message.Data["call_event"]; got != "call.accept" {
		t.Fatalf("call_event = %q", got)
	}
}

func TestNotifierTargetsRevokedRegistrationWithSessionReplacementIdentity(t *testing.T) {
	sender := &fakeSender{}
	notifier := NewPushNotifier(&fakePushTargetStore{}, sender, nil)

	notifier.SessionReplaced("alice", "old-session", []state.Device{
		{UserLogin: "alice", DeviceID: "old-phone", PushTarget: state.PushTarget{Kind: state.KindToken, Value: "old-fcm"}},
		{UserLogin: "alice", DeviceID: "unused"},
	})

	if sender.calls != 1 || sender.last.Message.Token != "old-fcm" {
		t.Fatalf("targeted sends = %d token %q, want one old-fcm delivery", sender.calls, sender.last.Message.Token)
	}
	want := map[string]string{
		"type":               "session_replaced",
		"login":              "alice",
		"revoked_session_id": "old-session",
		"revoked_device_id":  "old-phone",
	}
	for key, value := range want {
		if sender.last.Message.Data[key] != value {
			t.Fatalf("session replacement data[%q] = %q, want %q; data = %+v", key, sender.last.Message.Data[key], value, sender.last.Message.Data)
		}
	}
	if sender.last.Message.Android.TTL != "2419200s" {
		t.Fatalf("session replacement TTL = %q, want 28 days", sender.last.Message.Android.TTL)
	}
}

func TestSessionReplacedMessageIncludesEmptyLegacySessionID(t *testing.T) {
	msg := SessionReplacedMessage(state.PushTarget{Kind: state.KindToken, Value: "token"}, "alice", "", "old-phone")
	value, present := msg.Message.Data["revoked_session_id"]
	if !present || value != "" {
		t.Fatalf("revoked_session_id = %q present %v, want present empty legacy value", value, present)
	}
}

func TestNotifierAddsExactManagedTargetToEachCallRegistration(t *testing.T) {
	store := &fakePushTargetStore{targets: []state.Device{
		tokenDevice("phone", "phone-token"),
		tokenDevice("tablet", "tablet-token"),
	}}
	sender := &fakeSender{}
	notifier := NewPushNotifier(store, sender, nil)
	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{
		Event:              protocol.Event{CallID: "call-1"},
		TargetSessionKnown: true,
		TargetSessionID:    "bob-session",
		TargetDeviceID:     "phone",
	})

	if len(sender.requests) != 1 {
		t.Fatalf("targeted requests = %d, want only snapshotted phone registration", len(sender.requests))
	}
	request := sender.requests[0]
	if request.Message.Token != "phone-token" ||
		request.Message.Data["target_login"] != "bob" ||
		request.Message.Data["target_device_id"] != "phone" ||
		request.Message.Data["target_session_id"] != "bob-session" {
		t.Fatalf("request = token %q data %+v, want exact bob/phone/bob-session target", request.Message.Token, request.Message.Data)
	}
}

func TestNotifierSuppressesCallPushAfterTargetResolutionFailure(t *testing.T) {
	sender := &fakeSender{}
	notifier := NewPushNotifier(&fakePushTargetStore{targets: []state.Device{tokenDevice("phone", "token")}}, sender, nil)
	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{
		Event:                  protocol.Event{CallID: "call-1"},
		TargetResolutionFailed: true,
	})
	if sender.calls != 0 {
		t.Fatalf("send calls = %d, want fail-closed suppression", sender.calls)
	}
}

func TestProjectIDFromServiceAccount(t *testing.T) {
	project, err := ProjectIDFromServiceAccount([]byte(`{"project_id":"example-project"}`))
	if err != nil {
		t.Fatal(err)
	}
	if project != "example-project" {
		t.Fatalf("project = %q", project)
	}
}

func TestHTTPv1SenderSerializesExactlyOnePushTarget(t *testing.T) {
	tests := []struct {
		name     string
		target   state.PushTarget
		wantJSON string
	}{
		{
			name:     "token",
			target:   state.PushTarget{Kind: state.KindToken, Value: "token-1"},
			wantJSON: `{"message":{"token":"token-1","data":{"login":"alice","revoked_device_id":"phone","revoked_session_id":"session","type":"session_replaced"},"android":{"priority":"HIGH","ttl":"2419200s"}}}`,
		},
		{
			name:     "FID",
			target:   state.PushTarget{Kind: state.KindFID, Value: "fid-1", ConfigID: "firebase-config"},
			wantJSON: `{"message":{"fid":"fid-1","data":{"login":"alice","revoked_device_id":"phone","revoked_session_id":"session","type":"session_replaced"},"android":{"priority":"HIGH","ttl":"2419200s"}}}`,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var got string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, err := io.ReadAll(r.Body)
				if err != nil {
					t.Fatal(err)
				}
				got = string(body)
				w.WriteHeader(http.StatusOK)
			}))
			defer server.Close()
			sender := HTTPv1Sender{
				Client:      server.Client(),
				Endpoint:    server.URL,
				BearerToken: func() (string, error) { return "access-token", nil },
			}
			if err := sender.Send(SessionReplacedMessage(test.target, "alice", "session", "phone")); err != nil {
				t.Fatal(err)
			}
			if got != test.wantJSON {
				t.Fatalf("request JSON = %s, want %s", got, test.wantJSON)
			}
		})
	}
}

func TestNotifierDisablesOnlyInvalidFIDTarget(t *testing.T) {
	target := state.PushTarget{Kind: state.KindFID, Value: "fid-1", ConfigID: "firebase-config"}
	store := &fakePushTargetStore{targets: []state.Device{{DeviceID: "phone", PushTarget: target}}, displayName: "Alice"}
	sender := &fakeSender{err: ErrInvalidRegistration}
	notifier := NewPushNotifier(store, sender, nil)

	notifier.IncomingCall("alice", "bob", signaling.DeliveredEvent{Event: protocol.Event{CallID: "call-1"}})

	if sender.last.Message.Token != "" || sender.last.Message.FID != "fid-1" {
		t.Fatalf("message target = token %q FID %q, want only fid-1", sender.last.Message.Token, sender.last.Message.FID)
	}
	if store.disabled != target {
		t.Fatalf("disabled target = %+v, want %+v", store.disabled, target)
	}
}

func TestHTTPv1SenderInvalidatesOnlyUnregisteredTokensAndNotFoundFIDs(t *testing.T) {
	tests := []struct {
		name        string
		target      state.PushTarget
		status      int
		body        string
		wantInvalid bool
	}{
		{
			name:        "unregistered token",
			target:      state.PushTarget{Kind: state.KindToken, Value: "token"},
			status:      http.StatusNotFound,
			body:        `{"error":{"status":"NOT_FOUND","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`,
			wantInvalid: true,
		},
		{
			name:        "not found FID",
			target:      state.PushTarget{Kind: state.KindFID, Value: "fid", ConfigID: "firebase-config"},
			status:      http.StatusNotFound,
			body:        `{"error":{"status":"NOT_FOUND"}}`,
			wantInvalid: true,
		},
		{
			name:   "sender ID mismatch",
			target: state.PushTarget{Kind: state.KindToken, Value: "token"},
			status: http.StatusForbidden,
			body:   `{"error":{"status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"SENDER_ID_MISMATCH"}]}}`,
		},
		{
			name:   "token invalid argument",
			target: state.PushTarget{Kind: state.KindToken, Value: "token"},
			status: http.StatusBadRequest,
			body:   `{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"INVALID_ARGUMENT"}]}}`,
		},
		{
			name:   "FID invalid argument",
			target: state.PushTarget{Kind: state.KindFID, Value: "fid", ConfigID: "firebase-config"},
			status: http.StatusBadRequest,
			body:   `{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"INVALID_ARGUMENT"}]}}`,
		},
		{
			name:   "FID unregistered",
			target: state.PushTarget{Kind: state.KindFID, Value: "fid", ConfigID: "firebase-config"},
			status: http.StatusBadRequest,
			body:   `{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`,
		},
		{
			name:   "generic payload failure",
			target: state.PushTarget{Kind: state.KindToken, Value: "token"},
			status: http.StatusBadRequest,
			body:   `{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.BadRequest"}]}}`,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(test.status)
				_, _ = w.Write([]byte(test.body))
			}))
			defer server.Close()
			sender := HTTPv1Sender{
				Client:      server.Client(),
				Endpoint:    server.URL,
				BearerToken: func() (string, error) { return "access-token", nil },
			}
			err := sender.Send(SessionReplacedMessage(test.target, "alice", "session", "phone"))
			if err == nil {
				t.Fatal("Send error = nil, want FCM rejection")
			}
			if got := errors.Is(err, ErrInvalidRegistration); got != test.wantInvalid {
				t.Fatalf("invalid registration = %v, want %v; error = %v", got, test.wantInvalid, err)
			}
		})
	}
}

type fakePushTargetStore struct {
	targets     []state.Device
	disabled    state.PushTarget
	displayName string
	nameOwner   string
	nameContact string
}

func (s *fakePushTargetStore) PushTargetsForUser(string) ([]state.Device, error) {
	return s.targets, nil
}
func (s *fakePushTargetStore) ContactDisplayName(owner, contact string) (string, error) {
	s.nameOwner = owner
	s.nameContact = contact
	return s.displayName, nil
}
func (s *fakePushTargetStore) DisablePushTarget(target state.PushTarget) error {
	s.disabled = target
	return nil
}

func tokenDevice(deviceID, value string) state.Device {
	return state.Device{DeviceID: deviceID, PushTarget: state.PushTarget{Kind: state.KindToken, Value: value}}
}

type fakeSender struct {
	calls    int
	err      error
	last     WakeRequest
	requests []WakeRequest
}

type fakeWebPushSender struct {
	calls int
	err   error
	last  WebPushRequest
}

type captureWebPushClient struct {
	request *http.Request
	status  int
}

func (c *captureWebPushClient) Do(request *http.Request) (*http.Response, error) {
	c.request = request
	return &http.Response{StatusCode: c.status, Body: http.NoBody}, nil
}

func (s *fakeWebPushSender) Send(request WebPushRequest) error {
	s.calls++
	s.last = request
	return s.err
}

func (s *fakeSender) Send(request WakeRequest) error {
	s.calls++
	s.last = request
	s.requests = append(s.requests, request)
	return s.err
}
