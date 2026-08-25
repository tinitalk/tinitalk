package signaling

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"tinitalk/internal/protocol"
)

type Notifier interface {
	IncomingCall(callee string, event DeliveredEvent)
}

type NoopNotifier struct{}

func (NoopNotifier) IncomingCall(string, DeliveredEvent) {}

type ICEConfigProvider interface {
	ICEConfig(callID, user string) json.RawMessage
}

type Hub struct {
	mu           sync.Mutex
	notifier     Notifier
	iceConfig    ICEConfigProvider
	clients      map[string]*Client
	clientCounts map[string]int
	calls        map[string]*call
	activeByUser map[string]string
	now          func() time.Time
}

func NewHub(notifier Notifier) *Hub {
	if notifier == nil {
		notifier = NoopNotifier{}
	}
	return &Hub{
		notifier:     notifier,
		clients:      map[string]*Client{},
		clientCounts: map[string]int{},
		calls:        map[string]*call{},
		activeByUser: map[string]string{},
		now:          time.Now,
	}
}

func (h *Hub) SetICEConfigProvider(provider ICEConfigProvider) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.iceConfig = provider
}

func (h *Hub) Connect(user string) *Client {
	client, _ := h.ConnectChecked(user)
	return client
}

func (h *Hub) ConnectChecked(user string) (*Client, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clientCounts[user] >= MaxConnectionsPerUser {
		return nil, errors.New("too many connections for user")
	}
	client := &Client{user: user, inbox: make(chan DeliveredEvent, ReplayLimit)}
	h.clients[user] = client
	h.clientCounts[user]++
	return client, nil
}

func (h *Hub) SetNow(now func() time.Time) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.now = now
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
	if event.Type == "rtc.ice" {
		if err := h.checkICERate(c); err != nil {
			return err
		}
	}
	c.seen[event.ID] = struct{}{}
	delivered := h.next(c, event)
	h.deliver(c.other(sender), delivered)
	if event.Type == "call.accept" {
		c.answered = true
		h.deliverICEConfig(c)
	}
	if endsCall(event.Type) {
		h.end(c)
	}
	return nil
}

func (h *Hub) deliverICEConfig(c *call) {
	if h.iceConfig == nil {
		return
	}
	for _, participant := range []string{c.caller, c.callee} {
		event := protocol.Event{
			ID:      rtcConfigID(c.id, participant),
			CallID:  c.id,
			Type:    "rtc.config",
			SentAt:  h.now().UnixMilli(),
			Payload: h.iceConfig.ICEConfig(c.id, participant),
		}
		h.deliver(participant, h.next(c, event))
	}
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
		id:        event.CallID,
		caller:    sender,
		callee:    payload.CalleeID,
		seen:      map[string]struct{}{event.ID: {}},
		nextSeq:   1,
		startedAt: h.now(),
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

func (h *Hub) ExpireWaiting() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	now := h.now()
	expired := 0
	for _, c := range h.calls {
		if c.answered {
			continue
		}
		if now.Sub(c.startedAt) <= time.Duration(protocol.RingTimeoutSecs)*time.Second {
			continue
		}
		event := protocol.Event{
			ID:      expireID(c.id),
			CallID:  c.id,
			Type:    "call.expire",
			SentAt:  now.UnixMilli(),
			Payload: json.RawMessage(`{}`),
		}
		delivered := h.next(c, event)
		h.deliver(c.caller, delivered)
		h.deliver(c.callee, delivered)
		h.end(c)
		expired++
	}
	return expired
}

func (h *Hub) checkICERate(c *call) error {
	now := h.now()
	if c.iceWindowAt.IsZero() || now.Sub(c.iceWindowAt) >= time.Minute {
		c.iceWindowAt = now
		c.iceCount = 0
	}
	if c.iceCount >= MaxICEPerMinute {
		return errors.New("too many ICE events")
	}
	c.iceCount++
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

func expireID(callID string) string {
	return callID[:len(callID)-3] + "999"
}

func rtcConfigID(callID, participant string) string {
	if participant == "" || participant[0]%2 == 0 {
		return callID[:len(callID)-3] + "201"
	}
	return callID[:len(callID)-3] + "202"
}
