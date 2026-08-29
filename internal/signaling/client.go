package signaling

import "tinitalk/internal/protocol"

type DeliveredEvent struct {
	protocol.Event
	Seq                    uint64 `json:"seq"`
	TargetSessionID        string `json:"-"`
	TargetDeviceID         string `json:"-"`
	TargetSessionKnown     bool   `json:"-"`
	TargetResolutionFailed bool   `json:"-"`
}

type Client struct {
	user                 string
	deviceID             string
	sessionID            string
	inbox                chan DeliveredEvent
	closed               bool
	online               bool
	awaitingResumeCallID string
}

func (c *Client) TryNext() (DeliveredEvent, bool) {
	select {
	case event, ok := <-c.inbox:
		return event, ok
	default:
		return DeliveredEvent{}, false
	}
}

func (c *Client) Events() <-chan DeliveredEvent {
	return c.inbox
}
