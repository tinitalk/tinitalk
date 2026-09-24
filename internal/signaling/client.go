package signaling

import (
	"time"
	"tinitalk/internal/protocol"
)

type DeliveredEvent struct {
	protocol.Event
	Acknowledgement        string `json:"-"`
	Seq                    uint64 `json:"seq"`
	TargetSessionID        string `json:"-"`
	TargetDeviceID         string `json:"-"`
	TargetSessionKnown     bool   `json:"-"`
	TargetResolutionFailed bool   `json:"-"`
}

// Acknowledge queues the response after all events already delivered to this client.
// In particular, a resume acknowledgement must not overtake its replay.
func (h *Hub) Acknowledge(client *Client, eventID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.deliverClient(client, DeliveredEvent{Acknowledgement: eventID})
}

type Client struct {
	contactChanges              bool
	callWaiting                 bool
	user                        string
	deviceID                    string
	sessionID                   string
	inbox                       chan DeliveredEvent
	closed                      bool
	online                      bool
	awaitingResumeCallID        string
	foregroundCallNotifications bool
	visibleCallID               string
	visibleCallUntil            time.Time
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
