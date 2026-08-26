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
	online bool
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
