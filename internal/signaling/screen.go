package signaling

import (
	"encoding/json"
	"errors"

	"tinitalk/internal/protocol"
)

// Called under the hub lock: the first accepted request owns the single screen slot.
func (h *Hub) handleScreen(c *call, sender string, event protocol.Event) error {
	if !c.screenAllowed() {
		return errors.New("screen sharing is not allowed for this call")
	}
	var request struct {
		Enabled bool   `json:"enabled"`
		ShareID string `json:"share_id"`
	}
	if err := json.Unmarshal(event.Payload, &request); err != nil {
		return err
	}
	if request.Enabled {
		if c.screenPresenter != "" && (c.screenPresenter != sender || c.screenShareID != request.ShareID) {
			return clientError{message: "the other screen sharing session is still active", code: "screen_share_busy"}
		}
		c.screenPresenter, c.screenShareID = sender, request.ShareID
	} else if c.screenPresenter == sender && c.screenShareID == request.ShareID {
		c.screenPresenter, c.screenShareID = "", ""
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
	})
	source.SentAt = h.now().UnixMilli()
	device := c.deviceID(recipient)
	h.deliverDevice(recipient, device, h.nextDevice(c, source, recipient, device))
}
