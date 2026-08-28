package signaling

import (
	"testing"
	"time"

	"tinitalk/internal/protocol"
)

func TestHubRoutesActiveMediaOnlyToBoundDevices(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alicePhone := connectDevice(t, hub, "alice", "phone")
	aliceTablet := connectDevice(t, hub, "alice", "tablet")
	bobPhone := connectDevice(t, hub, "bob", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")

	start := event(uuid(2401), uuid(2402), "call.start", map[string]any{
		"callee_id": "bob", "supports_video": true,
	})
	if err := hub.HandleClient(alicePhone, start); err != nil {
		t.Fatal(err)
	}
	for name, client := range map[string]*Client{"phone": bobPhone, "tablet": bobTablet} {
		if got := next(t, client); got.Type != "call.incoming" {
			t.Fatalf("bob %s incoming = %+v", name, got)
		}
	}

	ringing := event(uuid(2403), start.CallID, "call.ringing", map[string]any{})
	if err := hub.HandleClient(bobPhone, ringing); err != nil {
		t.Fatal(err)
	}
	for name, client := range map[string]*Client{"phone": alicePhone, "tablet": aliceTablet} {
		if got := next(t, client); got.ID != ringing.ID {
			t.Fatalf("alice %s ringing = %+v", name, got)
		}
	}

	accept := event(uuid(2404), start.CallID, "call.accept", map[string]any{"supports_video": true})
	if err := hub.HandleClient(bobTablet, accept); err != nil {
		t.Fatal(err)
	}
	if got := next(t, alicePhone); got.ID != accept.ID || got.Type != "call.accept" {
		t.Fatalf("alice phone accept = %+v", got)
	}
	assertVideoAllowed(t, next(t, alicePhone).Event, true)
	assertVideoAllowed(t, next(t, bobTablet).Event, true)
	assertNoEvent(t, aliceTablet)
	assertNoEvent(t, bobPhone)

	assertBoundRoute(t, hub, alicePhone, bobTablet, aliceTablet, bobPhone,
		event(uuid(2405), start.CallID, "rtc.offer", map[string]any{"sdp": "offer"}))
	assertBoundRoute(t, hub, bobTablet, alicePhone, bobPhone, aliceTablet,
		event(uuid(2406), start.CallID, "rtc.answer", map[string]any{"sdp": "answer"}))
	assertBoundRoute(t, hub, alicePhone, bobTablet, aliceTablet, bobPhone,
		event(uuid(2407), start.CallID, "rtc.ice", map[string]any{"candidate": "caller"}))
	assertBoundRoute(t, hub, bobTablet, alicePhone, bobPhone, aliceTablet,
		event(uuid(2408), start.CallID, "rtc.ice", map[string]any{"candidate": "callee"}))
	assertBoundRoute(t, hub, bobTablet, alicePhone, bobPhone, aliceTablet,
		event(uuid(2409), start.CallID, "rtc.restart.request", map[string]any{}))

	restart := event(uuid(2410), start.CallID, "rtc.restart", map[string]any{})
	if err := hub.HandleClient(alicePhone, restart); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobTablet); got.ID != restart.ID {
		t.Fatalf("bob tablet restart = %+v", got)
	}
	assertVideoAllowed(t, next(t, alicePhone).Event, true)
	assertVideoAllowed(t, next(t, bobTablet).Event, true)
	assertNoEvent(t, aliceTablet)
	assertNoEvent(t, bobPhone)

	if err := hub.HandleClient(aliceTablet, event(uuid(2411), start.CallID, "rtc.offer", map[string]any{"sdp": "wrong device"})); err == nil {
		t.Fatal("unbound caller device sent an RTC offer")
	}

	end := event(uuid(2412), start.CallID, "call.end", map[string]any{})
	if err := hub.HandleClient(aliceTablet, end); err != nil {
		t.Fatalf("terminal event from another account device: %v", err)
	}
	for name, client := range map[string]*Client{"phone": bobPhone, "tablet": bobTablet} {
		if got := next(t, client); got.ID != end.ID {
			t.Fatalf("bob %s terminal event = %+v", name, got)
		}
	}
}

func TestHubRoutesAndReplaysRTCVideoOnlyToBoundPeerDevice(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alicePhone := connectDevice(t, hub, "alice", "phone")
	aliceTablet := connectDevice(t, hub, "alice", "tablet")
	bobPhone := connectDevice(t, hub, "bob", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")

	start := event(uuid(2501), uuid(2502), "call.start", map[string]any{
		"callee_id": "bob", "supports_video": true,
	})
	if err := hub.HandleClient(alicePhone, start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone)
	_ = next(t, bobTablet)
	accept := event(uuid(2503), start.CallID, "call.accept", map[string]any{"supports_video": true})
	if err := hub.HandleClient(bobTablet, accept); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alicePhone) // call.accept
	_ = next(t, alicePhone) // rtc.config
	_ = next(t, bobTablet)  // rtc.config
	assertNoEvent(t, aliceTablet)
	assertNoEvent(t, bobPhone)

	videoOn := event(uuid(2504), start.CallID, "rtc.video", map[string]any{"enabled": true})
	if err := hub.HandleClient(alicePhone, videoOn); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobTablet); got.ID != videoOn.ID || got.Seq != 5 {
		t.Fatalf("bob tablet video event = %+v", got)
	}
	assertNoEvent(t, aliceTablet)
	assertNoEvent(t, bobPhone)

	wrongDevice := event(uuid(2505), start.CallID, "rtc.video", map[string]any{"enabled": false})
	if err := hub.HandleClient(aliceTablet, wrongDevice); err == nil {
		t.Fatal("unbound caller device sent rtc.video")
	}
	assertNoEvent(t, bobTablet)
	assertNoEvent(t, bobPhone)

	bobTabletReplacement := connectDevice(t, hub, "bob", "tablet")
	resume := event(uuid(2506), start.CallID, "call.resume", map[string]any{"last_seq": 4})
	if err := hub.HandleClient(bobTabletReplacement, resume); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobTabletReplacement); got.ID != videoOn.ID {
		t.Fatalf("replayed video event = %+v", got)
	}
	assertNoEvent(t, bobPhone)

	videoOff := event(uuid(2507), start.CallID, "rtc.video", map[string]any{"enabled": false})
	if err := hub.HandleClient(bobTabletReplacement, videoOff); err != nil {
		t.Fatal(err)
	}
	if got := next(t, alicePhone); got.ID != videoOff.ID {
		t.Fatalf("alice phone video event = %+v", got)
	}
	assertNoEvent(t, aliceTablet)
}

func TestHubResumeAfterCallEndSkipsRTCVideoAndReplaysTerminalEvent(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alicePhone := connectDevice(t, hub, "alice", "phone")
	bobPhone := connectDevice(t, hub, "bob", "phone")

	start := event(uuid(2591), uuid(2592), "call.start", map[string]any{
		"callee_id": "bob", "supports_video": true,
	})
	if err := hub.HandleClient(alicePhone, start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone)
	if err := hub.HandleClient(bobPhone, event(uuid(2593), start.CallID, "call.accept", map[string]any{"supports_video": true})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alicePhone) // call.accept
	_ = next(t, alicePhone) // rtc.config
	_ = next(t, bobPhone)   // rtc.config

	video := event(uuid(2594), start.CallID, "rtc.video", map[string]any{"enabled": true})
	if err := hub.HandleClient(alicePhone, video); err != nil {
		t.Fatal(err)
	}
	deliveredVideo := next(t, bobPhone)
	end := event(uuid(2595), start.CallID, "call.end", map[string]any{})
	if err := hub.HandleClient(alicePhone, end); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone)

	bobReplacement := connectDevice(t, hub, "bob", "phone")
	resume := event(uuid(2596), start.CallID, "call.resume", map[string]any{"last_seq": deliveredVideo.Seq - 1})
	if err := hub.HandleClient(bobReplacement, resume); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobReplacement); got.ID != end.ID || got.Type != "call.end" {
		t.Fatalf("terminal replay = %+v", got)
	}
	assertNoEvent(t, bobReplacement)
}

func TestHubRejectsRTCVideoWithoutTwoVideoCapableBoundDevices(t *testing.T) {
	tests := []struct {
		name                string
		callerDeviceID      string
		calleeDeviceID      string
		callerSupportsVideo bool
		calleeSupportsVideo bool
		sender              string
	}{
		{
			name:                "caller lacks video support",
			callerDeviceID:      "phone",
			calleeDeviceID:      "tablet",
			calleeSupportsVideo: true,
			sender:              "bob",
		},
		{
			name:                "callee lacks video support",
			callerDeviceID:      "phone",
			calleeDeviceID:      "tablet",
			callerSupportsVideo: true,
			sender:              "alice",
		},
		{
			name:                "call devices are not bound",
			callerSupportsVideo: true,
			calleeSupportsVideo: true,
			sender:              "alice",
		},
	}
	for i, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			hub := NewHub(NoopNotifier{})
			alice := connectDevice(t, hub, "alice", test.callerDeviceID)
			bob := connectDevice(t, hub, "bob", test.calleeDeviceID)
			id := 2521 + i*10
			start := event(uuid(id), uuid(id+1), "call.start", map[string]any{
				"callee_id": "bob", "supports_video": test.callerSupportsVideo,
			})
			if err := hub.HandleClient(alice, start); err != nil {
				t.Fatal(err)
			}
			_ = next(t, bob)
			accept := event(uuid(id+2), start.CallID, "call.accept", map[string]any{
				"supports_video": test.calleeSupportsVideo,
			})
			if err := hub.HandleClient(bob, accept); err != nil {
				t.Fatal(err)
			}
			_ = next(t, alice) // call.accept
			_ = next(t, alice) // rtc.config
			_ = next(t, bob)   // rtc.config

			sender, recipient := alice, bob
			if test.sender == "bob" {
				sender, recipient = bob, alice
			}
			video := event(uuid(id+3), start.CallID, "rtc.video", map[string]any{"enabled": true})
			if err := hub.HandleClient(sender, video); err == nil {
				t.Fatal("rtc.video was accepted without two video-capable bound devices")
			}
			assertNoEvent(t, recipient)
		})
	}
}

func TestHubRebindsMediaOnSameDeviceReconnectAndFiltersReplay(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alicePhone := connectDevice(t, hub, "alice", "phone")
	aliceTablet := connectDevice(t, hub, "alice", "tablet")
	bobPhone := connectDevice(t, hub, "bob", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")
	start := startBoundCall(t, hub, alicePhone, bobPhone, bobTablet, 2421)

	offer := event(uuid(2425), start.CallID, "rtc.offer", map[string]any{"sdp": "offer"})
	if err := hub.HandleClient(alicePhone, offer); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobTablet); got.ID != offer.ID {
		t.Fatalf("initial offer = %+v", got)
	}

	resumeOther := event(uuid(2426), start.CallID, "call.resume", map[string]any{"last_seq": 4})
	if err := hub.HandleClient(bobPhone, resumeOther); err != nil {
		t.Fatal(err)
	}
	assertNoEvent(t, bobPhone)

	bobTabletReplacement := connectDevice(t, hub, "bob", "tablet")
	resumeBound := event(uuid(2427), start.CallID, "call.resume", map[string]any{"last_seq": 4})
	if err := hub.HandleClient(bobTabletReplacement, resumeBound); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobTabletReplacement); got.ID != offer.ID {
		t.Fatalf("same-device replay = %+v", got)
	}

	answer := event(uuid(2428), start.CallID, "rtc.answer", map[string]any{"sdp": "answer"})
	if err := hub.HandleClient(bobTabletReplacement, answer); err != nil {
		t.Fatal(err)
	}
	if got := next(t, alicePhone); got.ID != answer.ID {
		t.Fatalf("answer after reconnect = %+v", got)
	}
	assertNoEvent(t, aliceTablet)
}

func TestHubEmptyDeviceCannotReplayBoundMedia(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alicePhone := connectDevice(t, hub, "alice", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")
	bobWithoutID := connectDevice(t, hub, "bob", "")

	start := event(uuid(2431), uuid(2432), "call.start", map[string]any{
		"callee_id": "bob", "supports_video": true,
	})
	if err := hub.HandleClient(alicePhone, start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobTablet)
	_ = next(t, bobWithoutID)
	if err := hub.HandleClient(bobTablet, event(uuid(2433), start.CallID, "call.accept", map[string]any{"supports_video": true})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alicePhone) // call.accept
	assertVideoAllowed(t, next(t, alicePhone).Event, true)
	assertVideoAllowed(t, next(t, bobTablet).Event, true)
	assertNoEvent(t, bobWithoutID)

	offer := event(uuid(2434), start.CallID, "rtc.offer", map[string]any{"sdp": "offer"})
	if err := hub.HandleClient(alicePhone, offer); err != nil {
		t.Fatal(err)
	}
	if got := next(t, bobTablet); got.ID != offer.ID {
		t.Fatalf("bound offer = %+v", got)
	}

	resume := event(uuid(2435), start.CallID, "call.resume", map[string]any{"last_seq": 1})
	if err := hub.HandleClient(bobWithoutID, resume); err != nil {
		t.Fatal(err)
	}
	assertNoEvent(t, bobWithoutID)
	assertNoEvent(t, bobTablet)
}

func TestHubBoundDeviceDisconnectStartsGraceWhenOtherDeviceOnline(t *testing.T) {
	now := time.Unix(3_000, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alicePhone := connectDevice(t, hub, "alice", "phone")
	aliceTablet := connectDevice(t, hub, "alice", "tablet")
	bobPhone := connectDevice(t, hub, "bob", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")
	start := startBoundCall(t, hub, alicePhone, bobTablet, bobPhone, 2471)

	hub.Disconnect(alicePhone)
	now = now.Add(ActiveDisconnectGrace + time.Millisecond)
	if got := hub.Sweep(); got != 1 {
		t.Fatalf("Sweep() = %d, want bound-device disconnect to expire call", got)
	}
	if got := next(t, aliceTablet); got.Type != "call.end" || got.CallID != start.CallID {
		t.Fatalf("alice tablet terminal cleanup = %+v", got)
	}
}

func TestHubOtherDeviceConnectionDoesNotClearBoundDisconnectGrace(t *testing.T) {
	now := time.Unix(3_100, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alicePhone := connectDevice(t, hub, "alice", "phone")
	bobPhone := connectDevice(t, hub, "bob", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")
	_ = startBoundCall(t, hub, alicePhone, bobTablet, bobPhone, 2481)

	hub.Disconnect(alicePhone)
	_ = connectDevice(t, hub, "alice", "tablet")
	now = now.Add(ActiveDisconnectGrace + time.Millisecond)
	if got := hub.Sweep(); got != 1 {
		t.Fatalf("Sweep() = %d, want unrelated device to leave grace active", got)
	}
}

func TestHubOtherDeviceDisconnectDoesNotExtendBoundDisconnectGrace(t *testing.T) {
	now := time.Unix(3_150, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alicePhone := connectDevice(t, hub, "alice", "phone")
	aliceTablet := connectDevice(t, hub, "alice", "tablet")
	bobPhone := connectDevice(t, hub, "bob", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")
	_ = startBoundCall(t, hub, alicePhone, bobTablet, bobPhone, 2486)

	hub.Disconnect(alicePhone)
	now = now.Add(20 * time.Second)
	hub.Disconnect(aliceTablet)
	now = now.Add(ActiveDisconnectGrace - 20*time.Second + time.Millisecond)
	if got := hub.Sweep(); got != 1 {
		t.Fatalf("Sweep() = %d, want passive disconnect to preserve original grace", got)
	}
}

func TestHubAcceptTracksBoundCallerAlreadyOfflineDespiteOtherDevice(t *testing.T) {
	now := time.Unix(3_200, 0)
	hub := NewHub(NoopNotifier{})
	hub.SetNow(func() time.Time { return now })
	alicePhone := connectDevice(t, hub, "alice", "phone")
	_ = connectDevice(t, hub, "alice", "tablet")
	bobPhone := connectDevice(t, hub, "bob", "phone")

	start := event(uuid(2491), uuid(2492), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.HandleClient(alicePhone, start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone)
	hub.Disconnect(alicePhone)
	if err := hub.HandleClient(bobPhone, event(uuid(2493), start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone) // rtc.config

	now = now.Add(ActiveDisconnectGrace + time.Millisecond)
	if got := hub.Sweep(); got != 1 {
		t.Fatalf("Sweep() = %d, want already-offline bound caller to expire call", got)
	}
}

func TestHubMissingDeviceFallsBackToBroadcastAndDisablesVideo(t *testing.T) {
	for _, test := range []struct {
		name           string
		callerDeviceID string
		calleeDeviceID string
	}{
		{name: "caller missing", calleeDeviceID: "phone"},
		{name: "callee missing", callerDeviceID: "phone"},
	} {
		t.Run(test.name, func(t *testing.T) {
			hub := NewHub(NoopNotifier{})
			aliceActive := connectDevice(t, hub, "alice", test.callerDeviceID)
			aliceOther := connectDevice(t, hub, "alice", "tablet")
			bobActive := connectDevice(t, hub, "bob", test.calleeDeviceID)
			bobOther := connectDevice(t, hub, "bob", "tablet")

			start := event(uuid(2441), uuid(2442), "call.start", map[string]any{
				"callee_id": "bob", "supports_video": true,
			})
			if err := hub.HandleClient(aliceActive, start); err != nil {
				t.Fatal(err)
			}
			_ = next(t, bobActive)
			_ = next(t, bobOther)
			accept := event(uuid(2443), start.CallID, "call.accept", map[string]any{"supports_video": true})
			if err := hub.HandleClient(bobActive, accept); err != nil {
				t.Fatal(err)
			}
			for name, client := range map[string]*Client{"active": aliceActive, "other": aliceOther} {
				if got := next(t, client); got.ID != accept.ID {
					t.Fatalf("alice %s accept = %+v", name, got)
				}
				assertVideoAllowed(t, next(t, client).Event, false)
			}
			for name, client := range map[string]*Client{"active": bobActive, "other": bobOther} {
				config := next(t, client)
				if config.Type != "rtc.config" {
					t.Fatalf("bob %s config = %+v", name, config)
				}
				assertVideoAllowed(t, config.Event, false)
			}

			offer := event(uuid(2444), start.CallID, "rtc.offer", map[string]any{"sdp": "offer"})
			if err := hub.HandleClient(aliceActive, offer); err != nil {
				t.Fatal(err)
			}
			for name, client := range map[string]*Client{"active": bobActive, "other": bobOther} {
				if got := next(t, client); got.ID != offer.ID {
					t.Fatalf("bob %s fallback offer = %+v", name, got)
				}
			}
		})
	}
}

func TestHubBindsBothDevicesForCrossedCalls(t *testing.T) {
	hub := NewHub(NoopNotifier{})
	alicePhone := connectDevice(t, hub, "alice", "phone")
	aliceTablet := connectDevice(t, hub, "alice", "tablet")
	bobPhone := connectDevice(t, hub, "bob", "phone")
	bobTablet := connectDevice(t, hub, "bob", "tablet")

	first := event(uuid(2461), uuid(2462), "call.start", map[string]any{
		"callee_id": "bob", "supports_cross_call": true, "supports_video": true,
	})
	if err := hub.HandleClient(alicePhone, first); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone)
	_ = next(t, bobTablet)
	reverse := event(uuid(2463), uuid(2464), "call.start", map[string]any{
		"callee_id": "alice", "supports_cross_call": true, "supports_video": true,
	})
	if err := hub.HandleClient(bobTablet, reverse); err != nil {
		t.Fatal(err)
	}
	assertCrossedAccept(t, next(t, alicePhone), first.CallID, true)
	assertCrossedAccept(t, next(t, bobTablet), first.CallID, false)
	assertVideoAllowed(t, next(t, alicePhone).Event, true)
	assertVideoAllowed(t, next(t, bobTablet).Event, true)
	assertNoEvent(t, aliceTablet)
	assertNoEvent(t, bobPhone)
}

func connectDevice(t *testing.T, hub *Hub, user, deviceID string) *Client {
	t.Helper()
	client, err := hub.ConnectDeviceChecked(user, deviceID)
	if err != nil {
		t.Fatal(err)
	}
	if !hub.Connected(client) {
		t.Fatal("client did not connect")
	}
	return client
}

func startBoundCall(t *testing.T, hub *Hub, alicePhone, bobPhone, bobTablet *Client, id int) protocol.Event {
	t.Helper()
	start := event(uuid(id), uuid(id+1), "call.start", map[string]any{"callee_id": "bob"})
	if err := hub.HandleClient(alicePhone, start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bobPhone)
	_ = next(t, bobTablet)
	if err := hub.HandleClient(bobTablet, event(uuid(id+2), start.CallID, "call.accept", map[string]any{})); err != nil {
		t.Fatal(err)
	}
	_ = next(t, alicePhone)
	_ = next(t, alicePhone)
	_ = next(t, bobTablet)
	return start
}

func assertBoundRoute(t *testing.T, hub *Hub, sender, recipient, senderOther, recipientOther *Client, event protocol.Event) {
	t.Helper()
	if err := hub.HandleClient(sender, event); err != nil {
		t.Fatal(err)
	}
	if got := next(t, recipient); got.ID != event.ID {
		t.Fatalf("recipient event = %+v, want %s", got, event.ID)
	}
	assertNoEvent(t, senderOther)
	assertNoEvent(t, recipientOther)
}

func assertNoEvent(t *testing.T, client *Client) {
	t.Helper()
	if got, ok := client.TryNext(); ok {
		t.Fatalf("unexpected event = %+v", got)
	}
}
