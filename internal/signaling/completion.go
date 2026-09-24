package signaling

import (
	"encoding/json"
	"log"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/state"
)

// Client termination is committed before delivery or deduplication so the same
// request can be retried safely if persistence fails. All helpers hold h.mu.
func (h *Hub) completeClientCall(c *call, event protocol.Event, now time.Time, recipients, pushRecipients []string) error {
	busy := busyRejection(c, event)
	var rejection struct {
		Seen bool `json:"seen"`
	}
	if busy {
		_ = json.Unmarshal(event.Payload, &rejection)
		event.Payload = []byte(`{"reason":"busy"}`)
	}
	if h.history != nil {
		var err error
		if busy && rejection.Seen {
			err = h.history.FinishSeenBusyCall(c.id, now)
		} else if busy {
			err = h.history.FinishCall(c.id, state.CallOutcomeBusy, now)
		} else if event.Type == "call.reject" {
			var reply string
			reply, err = protocol.ParseCallReplyCode(event.Payload)
			if err == nil {
				err = h.history.FinishCallWithReply(c.id, state.CallOutcomeRejected, now, reply)
			}
		} else {
			err = h.history.FinishCall(c.id, outcomeForEvent(c, event.Type), now)
		}
		if err != nil {
			return err
		}
	}
	c.remember(event.ID)
	h.deliverCompletion(c, h.next(c, event, recipients...), recipients, pushRecipients)
	return nil
}

// Forced termination must release participants even when storage is unavailable.
func (h *Hub) completeServerCall(c *call, event protocol.Event, outcome state.CallOutcome, now time.Time, pushRecipients []string) {
	recipients := []string{c.caller, c.callee}
	delivered := h.next(c, event, recipients...)
	if h.history != nil {
		if err := h.history.FinishCall(c.id, outcome, now); err != nil {
			log.Printf("call history completion failed: call=%s: %v; retry scheduled", c.id, err)
			c.pendingHistory = &pendingCallHistory{
				outcome: outcome, endedAt: now, retryAt: now.Add(5 * time.Second), delay: 5 * time.Second,
			}
		}
	}
	h.deliverCompletion(c, delivered, recipients, pushRecipients)
}

func (h *Hub) deliverCompletion(c *call, delivered DeliveredEvent, recipients, pushRecipients []string) {
	for _, user := range recipients {
		h.deliver(user, delivered)
	}
	for _, user := range pushRecipients {
		h.enqueueNotification(notification{callee: user, event: delivered, cancel: true})
	}
	h.end(c)
}

type pendingCallHistory struct {
	outcome state.CallOutcome
	endedAt time.Time
	retryAt time.Time
	delay   time.Duration
}

// Retry only persistence, never signaling. Reuse the terminal replay retention
// rather than keeping an unbounded queue during a prolonged database outage.
func (h *Hub) retryHistory(c *call, now time.Time) {
	pending := c.pendingHistory
	if pending == nil {
		return
	}
	if now.Sub(c.endedAt) > TerminalRetention {
		log.Printf("call history completion abandoned: call=%s; terminal retention expired; startup recovery required", c.id)
		c.pendingHistory = nil
		return
	}
	if now.Before(pending.retryAt) {
		return
	}
	if err := h.history.FinishCall(c.id, pending.outcome, pending.endedAt); err != nil {
		pending.delay = min(pending.delay*2, 30*time.Second)
		pending.retryAt = now.Add(pending.delay)
		return
	}
	log.Printf("call history completion recovered: call=%s", c.id)
	c.pendingHistory = nil
}

func (h *Hub) end(c *call) {
	if c.state == callEnded {
		return
	}
	c.state = callEnded
	c.notifyPushWaiters()
	c.clearScreen()
	c.endedAt = h.now()
	for _, user := range []string{c.caller, c.callee} {
		if h.activeByUser[user] == c.id {
			delete(h.activeByUser, user)
		}
	}
	h.removeIncoming(c)
}

func outcomeForEvent(c *call, eventType string) state.CallOutcome {
	switch eventType {
	case "call.reject":
		return state.CallOutcomeRejected
	case "call.cancel":
		if c.ringingAt.IsZero() {
			return state.CallOutcomeCancelledBeforeRinging
		}
		return state.CallOutcomeCancelledAfterRinging
	case "call.expire":
		if c.ringingAt.IsZero() {
			return state.CallOutcomeUnreachable
		}
		return state.CallOutcomeUnanswered
	case "call.end":
		if c.connectedAt.IsZero() {
			return state.CallOutcomeConnectionFailed
		}
		return state.CallOutcomeCompleted
	default:
		return state.CallOutcomeInterruptedBeforeAnswer
	}
}

func disconnectedOutcome(c *call) state.CallOutcome {
	if c.connectedAt.IsZero() {
		return state.CallOutcomeConnectionFailed
	}
	return state.CallOutcomeInterrupted
}
