package signaling

import (
	"encoding/json"
	"errors"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/state"
)

const WaitingTimeout = 15 * time.Second
const MaxWaitingCalls = 8

// Support belongs to a live authenticated connection, never to the caller's
// assertion about the callee. Legacy/replaced devices retain immediate busy.
func (h *Hub) EnableCallWaiting(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if client != nil && !client.closed {
		client.callWaiting = true
	}
}

func (h *Hub) canReceiveWaiting(user string) bool {
	if len(h.incomingByUser[user]) >= MaxWaitingCalls {
		return false
	}
	active := h.calls[h.activeByUser[user]]
	if active != nil && (active.state != callActive || active.connectedAt.IsZero()) {
		return false
	}
	for client := range h.clients[user] {
		if !client.online || client.closed || !client.callWaiting {
			continue
		}
		if active != nil && h.clientBelongsToCall(active, client) {
			return true
		}
		// The ongoing conversation may belong to another server. Once this
		// device has acknowledged a waiting invitation, it may receive more.
		if active == nil {
			for _, incoming := range h.incomingByUser[user] {
				if incoming.waiting && h.now().Before(incoming.incomingDeadline()) &&
					incomingSnapshotMatches(incoming, client.deviceID, client.sessionID) {
					return true
				}
			}
		}
	}
	return false
}

func (c *call) incomingDeadline() time.Time {
	deadline := c.startedAt.Add(time.Duration(protocol.RingTimeoutSecs) * time.Second)
	if c.waiting && !c.waitingStartedAt.IsZero() {
		if waitingDeadline := c.waitingStartedAt.Add(WaitingTimeout); waitingDeadline.Before(deadline) {
			deadline = waitingDeadline
		}
	}
	return deadline
}

func (h *Hub) setCallWaiting(client *Client, c *call, event protocol.Event) error {
	if client == nil || !client.callWaiting || c.callee != client.user ||
		!incomingSnapshotMatches(c, client.deviceID, client.sessionID) {
		return errors.New("call waiting is not available to this device")
	}
	if c.state != callRinging {
		return errors.New("call is not ringing")
	}
	if !h.now().Before(c.incomingDeadline()) {
		h.expireIncoming(c)
		return errors.New("call has expired")
	}
	var payload struct {
		Waiting *bool `json:"waiting"`
	}
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return err
	}
	if payload.Waiting == nil {
		return errors.New("waiting is required")
	}
	if !*payload.Waiting && h.activeByUser[c.callee] != "" {
		return ErrCalleeBusy
	}
	if *payload.Waiting && c.ringingAt.IsZero() {
		if h.history != nil {
			if err := h.history.MarkCallRinging(c.id); err != nil {
				return err
			}
		}
		c.ringingAt = h.now()
		ringing := event
		ringing.Type, ringing.Payload = "call.ringing", json.RawMessage(`{}`)
		h.deliver(c.caller, h.next(c, ringing, c.caller))
	}
	c.waiting = *payload.Waiting
	if c.waiting && c.waitingStartedAt.IsZero() {
		c.waitingStartedAt = h.now()
	}
	c.remember(event.ID)
	event.Payload, _ = json.Marshal(map[string]any{
		"waiting": c.waiting, "expires_at": c.incomingDeadline().UTC().Format(time.RFC3339Nano),
		"remaining_ms": max(int64(0), c.incomingDeadline().Sub(h.now()).Milliseconds()),
	})
	h.deliverClient(client, h.nextDevice(c, event, c.callee, client.deviceID))
	c.notifyPushWaiters()
	return nil
}

func (h *Hub) expireIncoming(c *call) {
	event := protocol.Event{ID: expireID(c.id), CallID: c.id, Type: "call.expire", SentAt: h.now().UnixMilli(), Payload: json.RawMessage(`{}`)}
	outcome := outcomeForEvent(c, event.Type)
	if c.waiting {
		// Keep the terminal event understandable to callers predating call waiting.
		event.Type, event.Payload = "call.reject", json.RawMessage(`{"reason":"busy"}`)
		outcome = state.CallOutcomeBusy
	}
	h.completeServerCall(c, event, outcome, h.now(), []string{c.callee})
}

func (h *Hub) validateWaitingAcceptance(client *Client, c *call, event protocol.Event) (*call, error) {
	current := h.calls[h.activeByUser[c.callee]]
	if current == nil || current.id == c.id {
		return nil, nil
	}
	var payload struct {
		ReplaceCallID string `json:"replace_call_id"`
	}
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return nil, err
	}
	if client == nil || !client.callWaiting || payload.ReplaceCallID != current.id ||
		current.state != callActive || !h.clientBelongsToCall(current, client) ||
		!incomingSnapshotMatches(c, client.deviceID, client.sessionID) {
		return nil, ErrCalleeBusy
	}
	return current, nil
}

func busyRejection(c *call, event protocol.Event) bool {
	if event.Type != "call.reject" {
		return false
	}
	var payload struct {
		Reason    string `json:"reason"`
		ReplyCode string `json:"reply_code"`
	}
	_ = json.Unmarshal(event.Payload, &payload)
	return payload.ReplyCode == "" && (c.waiting || payload.Reason == "busy")
}
