package signaling

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"

	"tinitalk/internal/protocol"
)

type Notifier interface {
	IncomingCall(callee string, event DeliveredEvent)
}

type NoopNotifier struct{}

func (NoopNotifier) IncomingCall(string, DeliveredEvent) {}

type Hub struct {
	mu           sync.Mutex
	notifier     Notifier
	clients      map[string]*Client
	calls        map[string]*call
	activeByUser map[string]string
}

func NewHub(notifier Notifier) *Hub {
	if notifier == nil {
		notifier = NoopNotifier{}
	}
	return &Hub{
		notifier:     notifier,
		clients:      map[string]*Client{},
		calls:        map[string]*call{},
		activeByUser: map[string]string{},
	}
}

func (h *Hub) Connect(user string) *Client {
	h.mu.Lock()
	defer h.mu.Unlock()
	client := &Client{user: user, inbox: make(chan DeliveredEvent, ReplayLimit)}
	h.clients[user] = client
	return client
}

func (h *Hub) Handle(sender string, event protocol.Event) error {
	if err := event.Validate(); err != nil {
		return err
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if event.Type == "call.start" {
		return h.start(sender, event)
	}
	c, ok := h.calls[event.CallID]
	if !ok {
		return errors.New("call not found")
	}
	if _, ok := c.seen[event.ID]; ok {
		return nil
	}
	if !c.participant(sender) {
		return errors.New("sender is not a call participant")
	}
	c.seen[event.ID] = struct{}{}
	delivered := h.next(c, event)
	h.deliver(c.other(sender), delivered)
	if endsCall(event.Type) {
		h.end(c)
	}
	return nil
}

func (h *Hub) Resume(user, callID string, lastSeq uint64) ([]DeliveredEvent, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.calls[callID]
	if !ok {
		return nil, errors.New("call not found")
	}
	if !c.participant(user) {
		return nil, errors.New("user is not a call participant")
	}
	return c.after(lastSeq), nil
}

func (h *Hub) start(sender string, event protocol.Event) error {
	if existing, ok := h.calls[event.CallID]; ok {
		if _, seen := existing.seen[event.ID]; seen {
			return nil
		}
		return errors.New("call already exists")
	}
	if _, ok := h.activeByUser[sender]; ok {
		return errors.New("sender already has an active call")
	}
	var payload struct {
		CalleeID string `json:"callee_id"`
	}
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return err
	}
	if payload.CalleeID == "" {
		return errors.New("callee_id is required")
	}
	if _, ok := h.activeByUser[payload.CalleeID]; ok {
		return errors.New("callee already has an active call")
	}
	c := &call{
		id:      event.CallID,
		caller:  sender,
		callee:  payload.CalleeID,
		seen:    map[string]struct{}{event.ID: {}},
		nextSeq: 1,
	}
	h.calls[event.CallID] = c
	h.activeByUser[sender] = event.CallID
	h.activeByUser[payload.CalleeID] = event.CallID

	incoming := event
	incoming.Type = "call.incoming"
	delivered := h.next(c, incoming)
	h.deliver(payload.CalleeID, delivered)
	h.notifier.IncomingCall(payload.CalleeID, delivered)
	return nil
}

func (h *Hub) next(c *call, event protocol.Event) DeliveredEvent {
	delivered := DeliveredEvent{Event: event, Seq: c.nextSeq}
	c.nextSeq++
	c.appendReplay(delivered)
	return delivered
}

func (h *Hub) deliver(user string, event DeliveredEvent) {
	client := h.clients[user]
	if client == nil {
		return
	}
	select {
	case client.inbox <- event:
	default:
	}
}

func (h *Hub) end(c *call) {
	delete(h.activeByUser, c.caller)
	delete(h.activeByUser, c.callee)
}

func endsCall(eventType string) bool {
	switch eventType {
	case "call.reject", "call.cancel", "call.end", "call.expire":
		return true
	default:
		return false
	}
}

func (h *Hub) ActiveCall(user string) (string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	callID, ok := h.activeByUser[user]
	if !ok {
		return "", fmt.Errorf("active call not found")
	}
	return callID, nil
}
