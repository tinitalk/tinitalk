package signaling

import (
	"encoding/json"
	"testing"
	"time"
)

func TestScreenSharingNegotiationAndOwnership(t *testing.T) {
	for _, supported := range []bool{false, true} {
		t.Run(map[bool]string{false: "old peer", true: "both supported"}[supported], func(t *testing.T) {
			hub := NewHub(NoopNotifier{})
			a := connectDevice(t, hub, "alice", "phone")
			b := connectDevice(t, hub, "bob", "phone")
			callID := uuid(7001)
			must := func(err error) {
				t.Helper()
				if err != nil {
					t.Fatal(err)
				}
			}
			must(hub.HandleClient(a, event(uuid(7002), callID, "call.start", map[string]any{
				"callee_id": "bob", "supports_video": true, "supports_exclusive_screen_sharing": true,
			})))
			_ = next(t, b)
			must(hub.HandleClient(b, event(uuid(7003), callID, "call.accept", map[string]any{
				"supports_video": true, "supports_screen_sharing": true, "supports_exclusive_screen_sharing": supported,
			})))
			_ = next(t, a)
			var config map[string]any
			must(json.Unmarshal(next(t, a).Payload, &config))
			_ = next(t, b)
			if (config["screen_sharing_allowed"] == true) != supported {
				t.Fatal(config)
			}
			start := event(uuid(7004), callID, "rtc.screen", map[string]any{"enabled": true, "share_id": uuid(7005)})
			err := hub.HandleClient(a, start)
			if !supported {
				if err == nil {
					t.Fatal("old peer accepted screen sharing")
				}
				assertNoEvent(t, b)
				return
			}
			must(err)
			_ = next(t, a)
			_ = next(t, b)
			must(hub.HandleClient(a, start)) // Lost ACK must not claim twice.
			assertNoEvent(t, a)
			c := hub.calls[callID]
			if c.screenReady() {
				t.Fatal("capture allowed before cameras stopped")
			}
			must(hub.HandleClient(a, event(uuid(7020), callID, "rtc.screen.ready", map[string]any{"share_id": uuid(7005)})))
			_ = next(t, a)
			_ = next(t, b)
			if c.screenReady() {
				t.Fatal("capture allowed before receiver stopped camera")
			}
			if hub.HandleClient(a, event(uuid(7021), callID, "rtc.video", map[string]any{"enabled": true})) == nil {
				t.Fatal("video allowed during preparation")
			}
			must(hub.HandleClient(b, event(uuid(7022), callID, "rtc.screen.ready", map[string]any{"share_id": uuid(7007)})))
			assertNoEvent(t, a)
			must(hub.HandleClient(b, event(uuid(7023), callID, "rtc.screen.ready", map[string]any{"share_id": uuid(7005)})))
			var ready map[string]any
			must(json.Unmarshal(next(t, a).Payload, &ready))
			_ = next(t, b)
			if !c.screenReady() || ready["ready"] != true {
				t.Fatal("both camera acknowledgements must allow capture", ready)
			}
			must(hub.HandleClient(b, event(uuid(7024), callID, "rtc.screen.ready", map[string]any{"share_id": uuid(7005)})))
			assertNoEvent(t, a)
			if hub.HandleClient(b, event(uuid(7025), callID, "rtc.video", map[string]any{"enabled": true})) == nil {
				t.Fatal("viewer camera allowed during sharing")
			}
			if hub.HandleClient(b, event(uuid(7006), callID, "rtc.screen", map[string]any{"enabled": true, "share_id": uuid(7007)})) == nil {
				t.Fatal("two simultaneous presenters")
			}
			must(hub.HandleClient(a, event(uuid(7008), callID, "rtc.screen", map[string]any{"enabled": false, "share_id": uuid(7007)})))
			_ = next(t, a)
			_ = next(t, b)
			if hub.calls[callID].screenShareID != uuid(7005) {
				t.Fatal("stale stop cleared current share")
			}
			must(hub.HandleClient(a, event(uuid(7009), callID, "rtc.screen", map[string]any{"enabled": false, "share_id": uuid(7005)})))
			_ = next(t, a)
			_ = next(t, b)
			must(hub.HandleClient(b, event(uuid(7010), callID, "rtc.screen", map[string]any{"enabled": true, "share_id": uuid(7011)})))
			_ = next(t, a)
			_ = next(t, b)
			must(hub.HandleClient(a, event(uuid(7012), callID, "call.resume", map[string]any{"last_seq": 99999})))
			var snapshot map[string]any
			must(json.Unmarshal(next(t, a).Payload, &snapshot))
			if snapshot["presenter_id"] != "bob" {
				t.Fatal(snapshot)
			}
			for _, delivered := range hub.calls[callID].afterDevice("alice", "phone", 0) {
				if delivered.Type == "rtc.screen" {
					t.Fatal("resume must not replay old screen grants or stops")
				}
			}
			if c.screenReady() {
				t.Fatal("new share inherited previous readiness")
			}
			deadline := c.screenPreparingAt.Add(16 * time.Second)
			hub.now = func() time.Time { return deadline }
			hub.Sweep()
			if c.screenPresenter != "" || c.state != callActive {
				t.Fatal("preparation timeout must stop sharing but preserve audio")
			}
			_ = next(t, a)
			_ = next(t, b)
		})
	}
}
