package signaling

import (
	"encoding/json"
	"time"

	"tinitalk/internal/protocol"
)

// Called under the hub lock: the first accepted request owns the single screen slot.
func (h *Hub) handleScreen(c *call, sender string, event protocol.Event) error {
	if !c.screenAllowed() {
		return clientError{message: "screen sharing is not allowed for this call", code: "screen_share_unavailable"}
	}
	var request struct {
		Enabled bool   `json:"enabled"`
		ShareID string `json:"share_id"`
	}
	if err := json.Unmarshal(event.Payload, &request); err != nil {
		return err
	}
	if event.Type == "rtc.screen.ready" {
		if c.screenPresenter == "" || c.screenShareID != request.ShareID {
			c.remember(event.ID)
			return nil
		}
		if sender == c.caller {
			if c.screenCallerReady {
				c.remember(event.ID)
				return nil
			}
			c.screenCallerReady = true
		} else {
			if c.screenCalleeReady {
				c.remember(event.ID)
				return nil
			}
			c.screenCalleeReady = true
		}
	} else if request.Enabled {
		if c.screenPresenter != "" && (c.screenPresenter != sender || c.screenShareID != request.ShareID) {
			return clientError{message: "the other screen sharing session is still active", code: "screen_share_busy"}
		}
		if c.screenPresenter == "" {
			c.clearScreen()
			c.screenPresenter, c.screenShareID = sender, request.ShareID
			c.screenPreparingAt = h.now()
		}
	} else if c.screenPresenter == sender && c.screenShareID == request.ShareID {
		c.clearScreen()
	}
	c.remember(event.ID)
	for _, user := range []string{c.caller, c.callee} {
		h.deliverScreenState(c, user, event)
	}
	return nil
}

func (h *Hub) deliverScreenState(c *call, recipient string, source protocol.Event) {
	if c.state != callActive || !c.screenAllowed() {
		return
	}
	shareID := c.screenShareID
	if shareID == "" {
		shareID = source.ID
	}
	source.Type = "rtc.screen"
	source.Payload, _ = json.Marshal(map[string]any{
		"enabled": c.screenPresenter != "", "presenter_id": c.screenPresenter, "share_id": shareID,
		"ready": c.screenReady(),
	})
	source.SentAt = h.now().UnixMilli()
	device := c.deviceID(recipient)
	h.deliverDevice(recipient, device, h.nextDevice(c, source, recipient, device))
}

func (h *Hub) expireScreenPreparation(c *call, now time.Time) {
	if c.screenPresenter == "" || c.screenReady() || now.Sub(c.screenPreparingAt) < 15*time.Second {
		return
	}
	source := protocol.Event{ID: c.screenShareID, CallID: c.id}
	c.clearScreen()
	for _, user := range []string{c.caller, c.callee} {
		h.deliverScreenState(c, user, source)
	}
}
