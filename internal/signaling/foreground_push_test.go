package signaling

import (
	"testing"
	"testing/synctest"
	"time"
)

func foregroundCall(t *testing.T) (*Hub, *Client, *Client, string) {
	t.Helper()
	h := NewHub(nil)
	alice := h.Connect("alice")
	bob, err := h.ConnectSessionChecked("bob", "iphone", "session")
	if err != nil {
		t.Fatal(err)
	}
	h.EnableForegroundCallNotifications(bob)
	h.Connected(bob)
	start := event(uuid(8101), uuid(8102), "call.start", map[string]any{"callee_id": "bob"})
	if err := h.HandleClient(alice, start); err != nil {
		t.Fatal(err)
	}
	_ = next(t, bob)
	return h, alice, bob, start.CallID
}

func reportVisible(t *testing.T, h *Hub, client *Client, id string, visible bool, seq int) {
	t.Helper()
	if err := h.HandleClient(client, event(uuid(seq), id, "call.visibility", map[string]any{"visible": visible})); err != nil {
		t.Fatal(err)
	}
}

func TestForegroundPushGraceAndLostLease(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		h, alice, bob, id := foregroundCall(t)
		result := make(chan bool, 1)
		go func() { result <- h.WaitIncomingPush("bob", "iphone", "session", id) }()
		synctest.Wait()
		select {
		case <-result:
			t.Fatal("push sent before the screen could acknowledge")
		default:
		}
		time.Sleep(100 * time.Millisecond)
		reportVisible(t, h, bob, id, true, 8103)
		time.Sleep(2 * time.Second)
		reportVisible(t, h, bob, id, true, 8104)
		time.Sleep(2 * time.Second)
		synctest.Wait()
		select {
		case <-result:
			t.Fatal("push sent while visible lease was renewed")
		default:
		}
		if _, ok := alice.TryNext(); ok {
			t.Fatal("visibility heartbeat reached caller signaling")
		}
		time.Sleep(1100 * time.Millisecond)
		synctest.Wait()
		select {
		case send := <-result:
			if !send {
				t.Fatal("lost lease must fall back to push")
			}
		default:
			t.Fatal("suspended page still suppresses push")
		}
	})
}

func TestForegroundPushFallbacks(t *testing.T) {
	for _, scenario := range []string{"no_ack", "hidden", "disconnect", "accepted", "rejected", "cancelled", "session_replaced", "expired"} {
		t.Run(scenario, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				h, alice, bob, id := foregroundCall(t)
				if scenario != "no_ack" {
					reportVisible(t, h, bob, id, true, 8110)
				}
				result := make(chan bool, 1)
				go func() { result <- h.WaitIncomingPush("bob", "iphone", "session", id) }()
				synctest.Wait()
				switch scenario {
				case "no_ack":
					time.Sleep(600 * time.Millisecond)
				case "hidden":
					reportVisible(t, h, bob, id, false, 8111)
				case "disconnect":
					h.Disconnect(bob)
				case "session_replaced":
					h.ReplaceSession("bob", "replacement")
				case "accepted", "rejected", "cancelled":
					client, kind := bob, "call.accept"
					if scenario == "rejected" {
						kind = "call.reject"
					}
					if scenario == "cancelled" {
						client, kind = alice, "call.cancel"
					}
					if err := h.HandleClient(client, event(uuid(8112), id, kind, map[string]any{})); err != nil {
						t.Fatal(err)
					}
				case "expired":
					for n := 0; n < 46; n++ {
						reportVisible(t, h, bob, id, true, 8200+n)
						time.Sleep(time.Second)
					}
				}
				synctest.Wait()
				want := scenario == "no_ack" || scenario == "hidden" || scenario == "disconnect"
				select {
				case got := <-result:
					if got != want {
						t.Fatalf("send=%v, want %v", got, want)
					}
				default:
					t.Fatal("push waiter was not released")
				}
			})
		})
	}
}

func TestForegroundPushIsBoundToDeviceAndSession(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		h, alice, bob, id := foregroundCall(t)
		reportVisible(t, h, bob, id, true, 8120)
		if !h.WaitIncomingPush("bob", "another-device", "session", id) {
			t.Fatal("another device was suppressed")
		}
		if h.WaitIncomingPush("bob", "iphone", "old-session", id) {
			t.Fatal("revoked session received push")
		}
		if h.WaitIncomingPush("alice", "iphone", "session", id) {
			t.Fatal("caller received incoming push")
		}
		if err := h.HandleClient(alice, event(uuid(8121), id, "call.visibility", map[string]any{"visible": true})); err == nil {
			t.Fatal("caller acknowledged callee UI")
		}
		eve := h.Connect("eve")
		if err := h.HandleClient(eve, event(uuid(8122), id, "call.visibility", map[string]any{"visible": true})); err == nil {
			t.Fatal("third party acknowledged call")
		}
	})
}

func TestForegroundPushDoesNotWaitForLegacyOrOfflineClient(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		h, _, bob, id := foregroundCall(t)
		h.Disconnect(bob)
		if !h.WaitIncomingPush("bob", "iphone", "session", id) {
			t.Fatal("offline push suppressed")
		}
		legacy, err := h.ConnectSessionChecked("bob", "iphone", "session")
		if err != nil {
			t.Fatal(err)
		}
		h.Connected(legacy)
		started := time.Now()
		if !h.WaitIncomingPush("bob", "iphone", "session", id) {
			t.Fatal("legacy push suppressed")
		}
		if !time.Now().Equal(started) {
			t.Fatal("legacy client was delayed")
		}
	})
}
