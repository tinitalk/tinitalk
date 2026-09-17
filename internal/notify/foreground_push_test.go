package notify

import (
	"encoding/json"
	"strings"
	"testing"
	"testing/synctest"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func TestForegroundBrowserPushDoesNotBlockOtherDevices(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sender := &expiryPushSender{requests: make(chan WebPushRequest, 8)}
		browser := notifyTarget("iphone")
		browser.Subscription = strings.Replace(browser.Subscription, "https://fcm.distributor.unifiedpush.org/wpfcm?t=iphone", "https://web.push.apple.com/iphone", 1)
		n := NewPushNotifier(&fakePushTargetStore{targets: []state.Device{
			{DeviceID: "iphone", PushTarget: browser},
			{DeviceID: "android", PushTarget: notifyTarget("android")},
		}}, sender)
		h := signaling.NewHub(n)
		n.SetIncomingCallGate(h)
		alice := h.Connect("alice")
		bob, err := h.ConnectDeviceChecked("bob", "iphone")
		if err != nil {
			t.Fatal(err)
		}
		h.EnableForegroundCallNotifications(bob)
		h.Connected(bob)
		id := "018f7d51-40a1-7bb5-a2d0-7e47f9180501"
		start := protocol.Event{ID: id, CallID: id, Type: "call.start", SentAt: time.Now().UnixMilli(), Payload: json.RawMessage(`{"callee_id":"bob"}`)}
		if err := h.HandleClient(alice, start); err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		if got := nextExpiryPush(t, sender); got.Subscription != notifySubscription("android") {
			t.Fatal("native device delayed by visible browser")
		}
		visibility := protocol.Event{ID: "018f7d51-40a1-7bb5-a2d0-7e47f9180502", CallID: id, Type: "call.visibility", SentAt: time.Now().UnixMilli(), Payload: json.RawMessage(`{"visible":true}`)}
		if err := h.HandleClient(bob, visibility); err != nil {
			t.Fatal(err)
		}
		time.Sleep(time.Second)
		synctest.Wait()
		select {
		case <-sender.requests:
			t.Fatal("visible browser received duplicate push")
		default:
		}
		visibility.ID = "018f7d51-40a1-7bb5-a2d0-7e47f9180503"
		visibility.Payload = json.RawMessage(`{"visible":false}`)
		if err := h.HandleClient(bob, visibility); err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		got := nextExpiryPush(t, sender)
		if got.Subscription != browser.Subscription || got.Data["type"] != "incoming_call" {
			t.Fatalf("fallback = %+v", got)
		}
		if got.TTL > 44*time.Second || got.TTL <= 43*time.Second {
			t.Fatalf("deferred TTL=%s must retain original call expiry", got.TTL)
		}
		time.Sleep(5 * time.Second)
		synctest.Wait()
		select {
		case <-sender.requests:
			t.Fatal("more than one incoming push sent to browser")
		default:
		}
	})
}

func TestForegroundNativeSocketSuppressesAndFallsBackToPush(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		sender := &expiryPushSender{requests: make(chan WebPushRequest, 2)}
		native := notifyTarget("android")
		n := NewPushNotifier(&fakePushTargetStore{targets: []state.Device{
			{DeviceID: "android", PushTarget: native},
		}}, sender)
		h := signaling.NewHub(n)
		n.SetIncomingCallGate(h)
		alice := h.Connect("alice")
		bob, err := h.ConnectDeviceChecked("bob", "android")
		if err != nil {
			t.Fatal(err)
		}
		h.EnableForegroundCallNotifications(bob)
		h.Connected(bob)
		id := "018f7d51-40a1-7bb5-a2d0-7e47f9180521"
		start := protocol.Event{ID: id, CallID: id, Type: "call.start", SentAt: time.Now().UnixMilli(), Payload: json.RawMessage(`{"callee_id":"bob"}`)}
		if err := h.HandleClient(alice, start); err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		select {
		case request := <-sender.requests:
			t.Fatalf("native push bypassed foreground grace: %+v", request)
		default:
		}
		visibility := protocol.Event{ID: "018f7d51-40a1-7bb5-a2d0-7e47f9180522", CallID: id, Type: "call.visibility", SentAt: time.Now().UnixMilli(), Payload: json.RawMessage(`{"visible":true}`)}
		if err := h.HandleClient(bob, visibility); err != nil {
			t.Fatal(err)
		}
		time.Sleep(time.Second)
		synctest.Wait()
		select {
		case request := <-sender.requests:
			t.Fatalf("visible native socket received duplicate push: %+v", request)
		default:
		}
		visibility.ID = "018f7d51-40a1-7bb5-a2d0-7e47f9180523"
		visibility.Payload = json.RawMessage(`{"visible":false}`)
		if err := h.HandleClient(bob, visibility); err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		got := nextExpiryPush(t, sender)
		if got.Subscription != native.Subscription || got.Data["type"] != "incoming_call" {
			t.Fatalf("native fallback = %+v", got)
		}
	})
}

func TestCancelledBrowserPushIsNotSentAfterWaitingForCapacityOrRetry(t *testing.T) {
	for _, scenario := range []string{"capacity", "retry"} {
		t.Run(scenario, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				browser := notifyTarget("iphone")
				browser.Subscription = strings.Replace(browser.Subscription, "https://fcm.distributor.unifiedpush.org/wpfcm?t=iphone", "https://web.push.apple.com/iphone", 1)
				sender := &cancelDuringPushSender{}
				n := NewPushNotifier(&fakePushTargetStore{targets: []state.Device{{DeviceID: "iphone", PushTarget: browser}}}, sender)
				h := signaling.NewHub(n)
				n.SetIncomingCallGate(h)
				alice := h.Connect("alice")
				id := "018f7d51-40a1-7bb5-a2d0-7e47f9180511"
				cancel := func() {
					if err := h.HandleClient(alice, protocol.Event{ID: "018f7d51-40a1-7bb5-a2d0-7e47f9180512", CallID: id, Type: "call.cancel", Payload: json.RawMessage(`{}`)}); err != nil {
						t.Fatal(err)
					}
				}
				if scenario == "capacity" {
					for i := 0; i < MaxConcurrentWebPush; i++ {
						n.sendSlots <- struct{}{}
					}
				} else {
					sender.cancel = cancel
				}
				if err := h.HandleClient(alice, protocol.Event{ID: id, CallID: id, Type: "call.start", Payload: json.RawMessage(`{"callee_id":"bob"}`)}); err != nil {
					t.Fatal(err)
				}
				synctest.Wait()
				if scenario == "capacity" {
					cancel()
					for i := 0; i < MaxConcurrentWebPush; i++ {
						<-n.sendSlots
					}
				}
				time.Sleep(time.Second)
				synctest.Wait()
				want := 0
				if scenario == "retry" {
					want = 1
				}
				if sender.calls != want {
					t.Fatalf("incoming sends=%d, want %d; cancelled call was sent again", sender.calls, want)
				}
			})
		})
	}
}

type cancelDuringPushSender struct {
	calls  int
	cancel func()
}

func (s *cancelDuringPushSender) Send(WebPushRequest) error {
	s.calls++
	if s.cancel != nil && s.calls == 1 {
		s.cancel()
		return ErrTemporaryPushDelivery
	}
	return nil
}
