package signaling

import "tinitalk/internal/protocol"

type DeliveredEvent struct {
	protocol.Event
	Seq uint64 `json:"seq"`
}

type Client struct {
	user   string
	inbox  chan DeliveredEvent
	closed bool
}

func (c *Client) TryNext() (DeliveredEvent, bool) {
	select {
	case event := <-c.inbox:
		return event, true
	default:
		return DeliveredEvent{}, false
	}
}

func (c *Client) Events() <-chan DeliveredEvent {
	return c.inbox
}
