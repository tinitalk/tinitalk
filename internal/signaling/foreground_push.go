package signaling

import (
	"encoding/json"
	"errors"
	"sort"
	"time"

	"tinitalk/internal/protocol"
)

const foregroundPushGrace = 500 * time.Millisecond
const foregroundCallLease = 3 * time.Second

// EnableForegroundCallNotifications opts a negotiated connection into
// acknowledging its incoming screen. A connected legacy client is not enough.
func (h *Hub) EnableForegroundCallNotifications(client *Client) {
	h.TryEnableForegroundCallNotifications(client)
}

// TryEnableForegroundCallNotifications reports whether the connection can
// bind visibility to a concrete push target.
func (h *Hub) TryEnableForegroundCallNotifications(client *Client) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if client != nil && !client.closed && client.deviceID != "" {
		client.foregroundCallNotifications = true
		return true
	}
	return false
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
		deadline := c.incomingDeadline()
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
		!h.now().Before(c.incomingDeadline()) {
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

type ActiveCallSnapshot struct {
	CallID        string
	Incoming      *IncomingCallSnapshot
	IncomingCalls []IncomingCallSnapshot
}

type IncomingCallSnapshot struct {
	CallID      string
	Waiting     bool
	CallerLogin string
	StartedAt   time.Time
	ExpiresAt   time.Time
	LastSeq     uint64
}

// ActiveCallSnapshotForDevice returns one mutex-consistent view of the active
// call. Pending incoming details are restricted to the current callee session
// and device, while the legacy call ID remains available for active calls.
func (h *Hub) ActiveCallSnapshotForDevice(user, deviceID, sessionID string) (ActiveCallSnapshot, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	callID := h.primaryCallID(user)
	if callID == "" {
		return ActiveCallSnapshot{}, errors.New("active call not found")
	}
	c := h.calls[callID]
	if c == nil || c.state == callEnded {
		return ActiveCallSnapshot{}, errors.New("active call not found")
	}
	if deviceID != "" && c.devicesBound() && c.deviceID(user) != deviceID {
		return ActiveCallSnapshot{}, errors.New("active call not found")
	}
	snapshot := ActiveCallSnapshot{CallID: callID}
	for _, pending := range h.incomingByUser[user] {
		if !h.now().Before(pending.incomingDeadline()) || !incomingSnapshotMatches(pending, deviceID, sessionID) {
			continue
		}
		snapshot.IncomingCalls = append(snapshot.IncomingCalls, IncomingCallSnapshot{
			CallID: pending.id, Waiting: pending.waiting, CallerLogin: pending.caller,
			StartedAt: pending.startedAt, ExpiresAt: pending.incomingDeadline(), LastSeq: pending.incomingSeq,
		})
	}
	sort.Slice(snapshot.IncomingCalls, func(i, j int) bool {
		a, b := snapshot.IncomingCalls[i], snapshot.IncomingCalls[j]
		if a.StartedAt.Equal(b.StartedAt) {
			return a.CallID < b.CallID
		}
		return a.StartedAt.Before(b.StartedAt)
	})
	expiresAt := c.incomingDeadline()
	if c.state != callRinging || c.callee != user || !h.now().Before(expiresAt) ||
		!incomingSnapshotMatches(c, deviceID, sessionID) {
		return snapshot, nil
	}
	snapshot.Incoming = &IncomingCallSnapshot{
		CallID: c.id, Waiting: c.waiting,
		CallerLogin: c.caller,
		StartedAt:   c.startedAt,
		ExpiresAt:   expiresAt,
		LastSeq:     c.incomingSeq,
	}
	return snapshot, nil
}

func incomingSnapshotMatches(c *call, deviceID, sessionID string) bool {
	if c.incomingSeq == 0 || c.incomingTargetFailed {
		return false
	}
	if !c.incomingTargetKnown || c.incomingTargetID == "" {
		return sessionID == ""
	}
	if c.incomingTargetID != sessionID || c.incomingTargetDevice != deviceID {
		return false
	}
	return true
}
