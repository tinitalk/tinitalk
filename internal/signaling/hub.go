package signaling

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"tinitalk/internal/protocol"
)

type Notifier interface {
	IncomingCall(caller, callee string, event DeliveredEvent)
	CancelCall(callee string, event DeliveredEvent)
}

type NoopNotifier struct{}

func (NoopNotifier) IncomingCall(string, string, DeliveredEvent) {}
func (NoopNotifier) CancelCall(string, DeliveredEvent)           {}

type ICEConfigProvider interface {
	ICEConfig(callID, user string) json.RawMessage
}

type Hub struct {
	mu            sync.Mutex
	notifier      Notifier
	iceConfig     ICEConfigProvider
	clients       map[string]map[*Client]struct{}
	calls         map[string]*call
	activeByUser  map[string]string
	now           func() time.Time
	notifications []notification
	reservedWake  map[string]struct{}
	notifying     bool
}

const notificationQueueLimit = 64

type notification struct {
	caller string
	callee string
	event  DeliveredEvent
	cancel bool
}

func NewHub(notifier Notifier) *Hub {
	if notifier == nil {
		notifier = NoopNotifier{}
	}
	return &Hub{
		notifier:     notifier,
		clients:      map[string]map[*Client]struct{}{},
		calls:        map[string]*call{},
		activeByUser: map[string]string{},
		reservedWake: map[string]struct{}{},
		now:          time.Now,
	}
}

func (h *Hub) SetICEConfigProvider(provider ICEConfigProvider) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.iceConfig = provider
}

func (h *Hub) Connect(user string) *Client {
	client, err := h.ConnectChecked(user)
	if err != nil || !h.Connected(client) {
		return nil
	}
	return client
}

func (h *Hub) ConnectChecked(user string) (*Client, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.clients[user]) >= MaxConnectionsPerUser {
		return nil, errors.New("too many connections for user")
	}
	client := &Client{user: user, inbox: make(chan DeliveredEvent, ReplayLimit)}
	if h.clients[user] == nil {
		h.clients[user] = map[*Client]struct{}{}
	}
	h.clients[user][client] = struct{}{}
	return client, nil
}

func (h *Hub) Connected(client *Client) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if client == nil || client.closed {
		return false
	}
	if _, ok := h.clients[client.user][client]; !ok {
		return false
	}
	client.online = true
	if callID, ok := h.activeByUser[client.user]; ok {
		if c := h.calls[callID]; c != nil && c.state == callActive {
			delete(c.offlineSince, client.user)
		}
	}
	return true
}

func (h *Hub) Disconnect(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.disconnectLocked(client)
}

func (h *Hub) disconnectLocked(client *Client) {
	if client == nil || client.closed {
		return
	}
	wasOnline := client.online
	client.closed = true
	delete(h.clients[client.user], client)
	if len(h.clients[client.user]) == 0 {
		delete(h.clients, client.user)
	}
	if !h.hasOnlineClient(client.user) {
		if callID, ok := h.activeByUser[client.user]; ok {
			if c := h.calls[callID]; c != nil && c.state == callActive {
				_, alreadyOffline := c.offlineSince[client.user]
				if wasOnline || !alreadyOffline {
					c.offlineSince[client.user] = h.now()
				}
			}
		}
	}
	close(client.inbox)
}

func (h *Hub) hasOnlineClient(user string) bool {
	for client := range h.clients[user] {
		if client.online && !client.closed {
			return true
		}
	}
	return false
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
	if event.Type == "call.resume" {
		var payload struct {
			LastSeq uint64 `json:"last_seq"`
		}
		if err := json.Unmarshal(event.Payload, &payload); err != nil {
			return err
		}
		for _, delivered := range c.after(sender, payload.LastSeq) {
			h.deliver(sender, delivered)
		}
		return nil
	}
	if err := c.validateTransition(sender, event.Type); err != nil {
		return err
	}
	if event.Type == "rtc.ice" {
		if err := h.checkICERate(c); err != nil {
			return err
		}
	}
	if event.Type == "rtc.restart" {
		if err := h.checkRestartRate(c); err != nil {
			return err
		}
	}
	c.remember(event.ID)
	recipient := c.other(sender)
	delivered := h.next(c, event, recipient)
	h.deliver(recipient, delivered)
	if event.Type == "call.accept" {
		c.state = callActive
		for _, participant := range []string{c.caller, c.callee} {
			if !h.hasOnlineClient(participant) {
				c.offlineSince[participant] = h.now()
			}
		}
		h.deliverICEConfig(c, "")
	}
	if event.Type == "rtc.restart" {
		h.deliverICEConfig(c, event.ID)
	}
	if event.Type == "call.accept" || event.Type == "call.reject" || event.Type == "call.cancel" {
		h.enqueueNotification(notification{callee: c.callee, event: delivered, cancel: true})
	}
	if endsCall(event.Type) {
		h.end(c)
	}
	return nil
}

func (h *Hub) deliverICEConfig(c *call, restartID string) {
	for _, participant := range []string{c.caller, c.callee} {
		payload := json.RawMessage(`{"ice_servers":[]}`)
		if h.iceConfig != nil {
			payload = h.iceConfig.ICEConfig(c.id, participant)
		}
		if restartID != "" {
			payload = withRestartID(payload, restartID)
		}
		event := protocol.Event{
			ID:      rtcConfigID(c.id, participant),
			CallID:  c.id,
			Type:    "rtc.config",
			SentAt:  h.now().UnixMilli(),
			Payload: payload,
		}
		h.deliver(participant, h.next(c, event, participant))
	}
}

func withRestartID(payload json.RawMessage, restartID string) json.RawMessage {
	var config map[string]json.RawMessage
	if err := json.Unmarshal(payload, &config); err != nil {
		return payload
	}
	encodedID, err := json.Marshal(restartID)
	if err != nil {
		return payload
	}
	config["restart_id"] = encodedID
	updated, err := json.Marshal(config)
	if err != nil {
		return payload
	}
	return updated
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
	return c.after(user, lastSeq), nil
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
	if payload.CalleeID == sender {
		return errors.New("cannot call yourself")
	}
	if _, ok := h.activeByUser[payload.CalleeID]; ok {
		return errors.New("callee already has an active call")
	}
	c := &call{
		id:           event.CallID,
		caller:       sender,
		callee:       payload.CalleeID,
		seen:         map[string]struct{}{},
		offlineSince: map[string]time.Time{},
		nextSeq:      1,
		startedAt:    h.now(),
		state:        callRinging,
	}
	c.remember(event.ID)
	h.calls[event.CallID] = c
	h.activeByUser[sender] = event.CallID
	h.activeByUser[payload.CalleeID] = event.CallID

	incoming := event
	incoming.Type = "call.incoming"
	incoming.SentAt = h.now().UnixMilli()
	delivered := h.next(c, incoming, payload.CalleeID)
	h.deliver(payload.CalleeID, delivered)
	h.enqueueNotification(notification{caller: sender, callee: payload.CalleeID, event: delivered})
	return nil
}

func (h *Hub) ExpireWaiting() int {
	return h.Sweep()
}

func (h *Hub) Sweep() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	now := h.now()
	expired := 0
	for callID, c := range h.calls {
		if c.state == callEnded {
			if now.Sub(c.endedAt) > TerminalRetention {
				delete(h.calls, callID)
				delete(h.reservedWake, callID)
			}
			continue
		}
		if c.state == callActive {
			for _, participant := range []string{c.caller, c.callee} {
				offlineSince, ok := c.offlineSince[participant]
				if !ok || now.Sub(offlineSince) <= ActiveDisconnectGrace {
					continue
				}
				event := protocol.Event{
					ID:      disconnectID(c.id),
					CallID:  c.id,
					Type:    "call.end",
					SentAt:  now.UnixMilli(),
					Payload: json.RawMessage(`{"reason":"participant_disconnected"}`),
				}
				delivered := h.next(c, event, c.caller, c.callee)
				h.deliver(c.caller, delivered)
				h.deliver(c.callee, delivered)
				h.end(c)
				expired++
				break
			}
			continue
		}
		if c.state != callRinging {
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
		delivered := h.next(c, event, c.caller, c.callee)
		h.deliver(c.caller, delivered)
		h.deliver(c.callee, delivered)
		h.enqueueNotification(notification{callee: c.callee, event: delivered, cancel: true})
		h.end(c)
		expired++
	}
	return expired
}

func (h *Hub) enqueueNotification(next notification) {
	for i := range h.notifications {
		if h.notifications[i].event.CallID != next.event.CallID {
			continue
		}
		if next.cancel {
			h.notifications[i] = next
		}
		return
	}
	if len(h.notifications) >= notificationQueueLimit {
		if !next.cancel {
			return
		}
		for i := range h.notifications {
			if i == 0 && h.notifying {
				continue
			}
			if !h.notifications[i].cancel {
				h.notifications = append(h.notifications[:i], h.notifications[i+1:]...)
				break
			}
		}
		if len(h.notifications) >= notificationQueueLimit {
			if _, protected := h.reservedWake[next.event.CallID]; !protected {
				return
			}
			for i := range h.notifications {
				if i == 0 && h.notifying {
					continue
				}
				if _, reserved := h.reservedWake[h.notifications[i].event.CallID]; !reserved {
					h.notifications = append(h.notifications[:i], h.notifications[i+1:]...)
					break
				}
			}
		}
		if len(h.notifications) >= notificationQueueLimit {
			return
		}
	}
	h.notifications = append(h.notifications, next)
	if h.notifying {
		return
	}
	h.notifying = true
	go h.runNotifications()
}

func (h *Hub) runNotifications() {
	for {
		h.mu.Lock()
		if len(h.notifications) == 0 {
			h.notifying = false
			h.mu.Unlock()
			return
		}
		next := h.notifications[0]
		if !next.cancel {
			if _, reserved := h.reservedWake[next.event.CallID]; !reserved {
				if len(h.reservedWake) >= notificationQueueLimit {
					h.notifications = h.notifications[1:]
					h.mu.Unlock()
					continue
				}
				h.reservedWake[next.event.CallID] = struct{}{}
			}
		}
		h.mu.Unlock()
		h.sendNotification(next)

		h.mu.Lock()
		if next.cancel {
			delete(h.reservedWake, next.event.CallID)
		}
		if h.notifications[0].cancel != next.cancel {
			h.mu.Unlock()
			continue
		}
		h.notifications = h.notifications[1:]
		h.mu.Unlock()
	}
}

func (h *Hub) sendNotification(next notification) {
	if next.cancel {
		h.notifier.CancelCall(next.callee, next.event)
	} else {
		h.notifier.IncomingCall(next.caller, next.callee, next.event)
	}
}

func (h *Hub) Run(ctx context.Context) {
	ticker := time.NewTicker(SweepInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			h.Sweep()
		}
	}
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

func (h *Hub) checkRestartRate(c *call) error {
	now := h.now()
	if !c.lastRestart.IsZero() && now.Sub(c.lastRestart) < RestartMinInterval {
		return errors.New("ICE restart requested too often")
	}
	c.lastRestart = now
	return nil
}

func (h *Hub) next(c *call, event protocol.Event, recipients ...string) DeliveredEvent {
	delivered := DeliveredEvent{Event: event, Seq: c.nextSeq}
	c.nextSeq++
	for _, recipient := range recipients {
		c.appendReplay(recipient, delivered)
	}
	return delivered
}

func (h *Hub) deliver(user string, event DeliveredEvent) {
	for client := range h.clients[user] {
		select {
		case client.inbox <- event:
		default:
			h.disconnectLocked(client)
		}
	}
}

func (h *Hub) end(c *call) {
	if c.state == callEnded {
		return
	}
	c.state = callEnded
	c.endedAt = h.now()
	delete(h.activeByUser, c.caller)
	delete(h.activeByUser, c.callee)
}

func (c *call) validateTransition(sender, eventType string) error {
	if c.state == callEnded {
		return errors.New("call has ended")
	}
	if c.state == callRinging {
		switch eventType {
		case "call.ringing", "call.accept", "call.reject":
			if sender != c.callee {
				return errors.New("only callee can send this event")
			}
			return nil
		case "call.cancel":
			if sender != c.caller {
				return errors.New("only caller can cancel")
			}
			return nil
		default:
			return errors.New("event is not allowed before call acceptance")
		}
	}
	switch eventType {
	case "call.end", "rtc.ice":
		return nil
	case "rtc.offer", "rtc.restart":
		if sender != c.caller {
			return errors.New("only caller can send this event")
		}
		return nil
	case "rtc.answer":
		if sender != c.callee {
			return errors.New("only callee can answer")
		}
		return nil
	}
	return errors.New("event is not allowed for active call")
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

func disconnectID(callID string) string {
	return callID[:len(callID)-3] + "998"
}

func rtcConfigID(callID, participant string) string {
	if participant == "" || participant[0]%2 == 0 {
		return callID[:len(callID)-3] + "201"
	}
	return callID[:len(callID)-3] + "202"
}
