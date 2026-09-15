package signaling

import (
	"encoding/json"
	"errors"
	"time"

	"tinitalk/internal/protocol"
)

const foregroundPushGrace = 500 * time.Millisecond
const foregroundCallLease = 3 * time.Second

// EnableForegroundCallNotifications opts a negotiated browser connection into
// acknowledging its incoming screen. A connected legacy client is not enough.
func (h *Hub) EnableForegroundCallNotifications(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if client != nil && !client.closed && client.deviceID != "" {
		client.foregroundCallNotifications = true
	}
}

func (h *Hub) callVisibility(client *Client, event protocol.Event) error {
	if client == nil || !client.foregroundCallNotifications {
		return errors.New("foreground call notifications were not negotiated")
	}
	c, ok := h.callByID(event.CallID)
	if !ok {
		return nil
	} // A late, non-replayed heartbeat needs no recovery.
	if c.callee != client.user {
		return errors.New("only the callee can acknowledge the incoming screen")
	}
	if c.state != callRinging {
		return nil
	}
	var payload struct {
		Visible bool `json:"visible"`
	}
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return err
	}
	client.visibleCallID = c.id
	client.visibleCallUntil = time.Time{}
	if payload.Visible {
		client.visibleCallUntil = h.now().Add(foregroundCallLease)
	}
	c.notifyPushWaiters()
	return nil
}

// WaitIncomingPush returns whether this particular device still needs an
// incoming push. It runs outside the notification queue and hub lock, so a
// visible page never delays another user's notifications. Waiting ends on
// hiding, disconnect, acceptance, cancellation, lease expiry or call expiry.
func (h *Hub) WaitIncomingPush(user, deviceID, sessionID, callID string) bool {
	for {
		h.mu.Lock()
		c := h.pendingIncomingPush(user, sessionID, callID)
		now := h.now()
		if c == nil {
			h.mu.Unlock()
			return false
		}
		var waitUntil time.Time
		for client := range h.clients[user] {
			if client.deviceID != deviceID || client.sessionID != sessionID ||
				client.closed || !client.online || !client.foregroundCallNotifications {
				continue
			}
			if client.visibleCallID == c.id {
				waitUntil = client.visibleCallUntil
			} else {
				waitUntil = c.startedAt.Add(foregroundPushGrace)
			}
			break
		}
		if !waitUntil.After(now) {
			h.mu.Unlock()
			return true
		}
		deadline := c.startedAt.Add(time.Duration(protocol.RingTimeoutSecs) * time.Second)
		if deadline.Before(waitUntil) {
			waitUntil = deadline
		}
		if c.pushChanged == nil {
			c.pushChanged = make(chan struct{})
		}
		changed := c.pushChanged
		h.mu.Unlock()
		timer := time.NewTimer(waitUntil.Sub(now))
		select {
		case <-changed:
			timer.Stop()
		case <-timer.C:
		}
	}
}

// IncomingPushPending rechecks validity after push transport backpressure and
// before retries. It never waits for foreground visibility while holding a
// transport slot.
func (h *Hub) IncomingPushPending(user, sessionID, callID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.pendingIncomingPush(user, sessionID, callID) != nil
}

func (h *Hub) pendingIncomingPush(user, sessionID, callID string) *call {
	c, ok := h.callByID(callID)
	currentSession, managed := h.sessions[user]
	if !ok || c.callee != user || c.state != callRinging ||
		(managed && currentSession != sessionID) ||
		!h.now().Before(c.startedAt.Add(time.Duration(protocol.RingTimeoutSecs)*time.Second)) {
		return nil
	}
	return c
}

func (c *call) notifyPushWaiters() {
	if c.pushChanged != nil {
		close(c.pushChanged)
		c.pushChanged = nil
	}
}
