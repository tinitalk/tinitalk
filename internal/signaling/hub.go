package signaling

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/state"
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

type CallHistoryStore interface {
	StartCall(callID, caller, callee string, startedAt time.Time) error
	RecordBusyCall(callID, caller, callee string, startedAt time.Time) error
	MarkCallRinging(callID string) error
	MarkCallAccepted(callID string) error
	MarkCallConnected(callID string, connectedAt time.Time) error
	FinishCall(callID string, outcome state.CallOutcome, endedAt time.Time) error
}

type Hub struct {
	mu            sync.Mutex
	notifier      Notifier
	iceConfig     ICEConfigProvider
	history       CallHistoryStore
	clients       map[string]map[*Client]struct{}
	calls         map[string]*call
	callAliases   map[string]string
	activeByUser  map[string]string
	now           func() time.Time
	notifications []notification
	reservedWake  map[string]struct{}
	notifying     bool
}

const notificationQueueLimit = 64

var ErrCalleeBusy = errors.New("callee already has an active call")

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
		callAliases:  map[string]string{},
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

func (h *Hub) SetCallHistoryStore(store CallHistoryStore) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.history = store
}

func (h *Hub) Connect(user string) *Client {
	client, err := h.ConnectChecked(user)
	if err != nil || !h.Connected(client) {
		return nil
	}
	return client
}

func (h *Hub) ConnectChecked(user string) (*Client, error) {
	return h.ConnectDeviceChecked(user, "")
}

func (h *Hub) ConnectDeviceChecked(user, deviceID string) (*Client, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if deviceID != "" {
		for client := range h.clients[user] {
			if client.deviceID == deviceID {
				h.disconnectLocked(client)
			}
		}
	}
	if len(h.clients[user]) >= MaxConnectionsPerUser {
		return nil, errors.New("too many connections for user")
	}
	client := &Client{user: user, deviceID: deviceID, inbox: make(chan DeliveredEvent, ReplayLimit)}
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
			if h.clientBelongsToCall(c, client) {
				delete(c.offlineSince, client.user)
			}
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
	if callID, ok := h.activeByUser[client.user]; ok {
		if c := h.calls[callID]; c != nil && c.state == callActive {
			if !h.hasOnlineCallClient(c, client.user) {
				_, alreadyOffline := c.offlineSince[client.user]
				if c.devicesBound() && h.clientBelongsToCall(c, client) && wasOnline {
					c.offlineSince[client.user] = h.now()
				} else if !c.devicesBound() && (wasOnline || !alreadyOffline) {
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

func (h *Hub) hasOnlineCallClient(c *call, user string) bool {
	if !c.devicesBound() {
		return h.hasOnlineClient(user)
	}
	deviceID := c.deviceID(user)
	for client := range h.clients[user] {
		if client.deviceID == deviceID && client.online && !client.closed {
			return true
		}
	}
	return false
}

func (h *Hub) clientBelongsToCall(c *call, client *Client) bool {
	return !c.devicesBound() || c.deviceID(client.user) == client.deviceID
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
	return h.handleLocked(sender, "", false, event)
}

func (h *Hub) HandleClient(client *Client, event protocol.Event) error {
	if client == nil {
		return errors.New("client is required")
	}
	if err := event.Validate(); err != nil {
		return err
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if client.closed || !client.online {
		return errors.New("client is not connected")
	}
	if _, ok := h.clients[client.user][client]; !ok {
		return errors.New("client is not connected")
	}
	return h.handleLocked(client.user, client.deviceID, true, event)
}

func (h *Hub) handleLocked(sender, senderDeviceID string, clientAware bool, event protocol.Event) error {
	if event.Type == "call.start" {
		return h.start(sender, senderDeviceID, event)
	}
	c, ok := h.callByID(event.CallID)
	if !ok {
		return errors.New("call not found")
	}
	event.CallID = c.id
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
		replayed := c.after(sender, payload.LastSeq)
		if clientAware {
			replayed = c.afterDevice(sender, senderDeviceID, payload.LastSeq)
		}
		for _, delivered := range replayed {
			if clientAware && senderDeviceID != "" {
				h.deliverDevice(sender, senderDeviceID, delivered)
			} else {
				h.deliver(sender, delivered)
			}
		}
		return nil
	}
	if event.Type == "rtc.video" && !c.videoAllowed() {
		return errors.New("video is not allowed for this call")
	}
	if c.devicesBound() && isDeviceBoundEvent(event.Type) && c.deviceID(sender) != senderDeviceID {
		return errors.New("event is not from the active call device")
	}
	if err := c.validateTransition(sender, event.Type); err != nil {
		return err
	}
	if event.Type == "call.accept" {
		var payload struct {
			SupportsVideo bool `json:"supports_video"`
		}
		if err := json.Unmarshal(event.Payload, &payload); err != nil {
			return err
		}
		c.calleeSupportsVideo = payload.SupportsVideo
		c.calleeDeviceID = senderDeviceID
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
	if event.Type == "rtc.restart.request" {
		if err := h.checkRestartRequestRate(c); err != nil {
			return err
		}
	}
	now := h.now()
	if h.history != nil {
		switch event.Type {
		case "call.ringing":
			if err := h.history.MarkCallRinging(c.id); err != nil {
				return err
			}
		case "call.accept":
			if err := h.history.MarkCallAccepted(c.id); err != nil {
				return err
			}
		case "call.connected":
			if c.connectedAt.IsZero() {
				if err := h.history.MarkCallConnected(c.id, now); err != nil {
					return err
				}
			}
		default:
			if endsCall(event.Type) {
				if err := h.history.FinishCall(c.id, outcomeForEvent(c, event.Type), now); err != nil {
					return err
				}
			}
		}
	}
	if event.Type == "call.ringing" && c.ringingAt.IsZero() {
		c.ringingAt = now
	}
	if event.Type == "call.connected" && c.connectedAt.IsZero() {
		c.connectedAt = now
	}
	c.remember(event.ID)
	recipient := c.other(sender)
	var delivered DeliveredEvent
	if isDeviceBoundEvent(event.Type) && c.devicesBound() {
		delivered = h.nextDevice(c, event, recipient, c.deviceID(recipient))
		h.deliverDevice(recipient, c.deviceID(recipient), delivered)
	} else {
		delivered = h.next(c, event, recipient)
		h.deliver(recipient, delivered)
	}
	if event.Type == "call.accept" {
		c.state = callActive
		for _, participant := range []string{c.caller, c.callee} {
			if !h.hasOnlineCallClient(c, participant) {
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
		payload = withVideoAllowed(payload, c.videoAllowed())
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
		if c.devicesBound() {
			deviceID := c.deviceID(participant)
			h.deliverDevice(participant, deviceID, h.nextDevice(c, event, participant, deviceID))
		} else {
			h.deliver(participant, h.next(c, event, participant))
		}
	}
}

func withVideoAllowed(payload json.RawMessage, videoAllowed bool) json.RawMessage {
	var config map[string]json.RawMessage
	if err := json.Unmarshal(payload, &config); err != nil {
		return payload
	}
	encoded, err := json.Marshal(videoAllowed)
	if err != nil {
		return payload
	}
	config["video_allowed"] = encoded
	updated, err := json.Marshal(config)
	if err != nil {
		return payload
	}
	return updated
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
	c, ok := h.callByID(callID)
	if !ok {
		return nil, errors.New("call not found")
	}
	if !c.participant(user) {
		return nil, errors.New("user is not a call participant")
	}
	return c.after(user, lastSeq), nil
}

func (h *Hub) start(sender, senderDeviceID string, event protocol.Event) error {
	if existing, ok := h.callByID(event.CallID); ok {
		if _, seen := existing.seen[event.ID]; seen {
			return nil
		}
		return errors.New("call already exists")
	}
	var payload struct {
		CalleeID          string `json:"callee_id"`
		SupportsCrossCall bool   `json:"supports_cross_call"`
		SupportsVideo     bool   `json:"supports_video"`
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
	if callID, ok := h.activeByUser[sender]; ok {
		existing := h.calls[callID]
		if existing != nil {
			if _, seen := existing.seen[event.ID]; seen {
				return nil
			}
			if existing.state == callRinging &&
				existing.caller == payload.CalleeID &&
				existing.callee == sender &&
				existing.supportsCrossCall && payload.SupportsCrossCall {
				return h.acceptCrossed(existing, senderDeviceID, event, payload.SupportsVideo)
			}
		}
		return ErrCalleeBusy
	}
	if _, ok := h.activeByUser[payload.CalleeID]; ok {
		if h.history != nil {
			if err := h.history.RecordBusyCall(event.CallID, sender, payload.CalleeID, h.now()); err != nil {
				return err
			}
		}
		busy := DeliveredEvent{Event: event}
		busy.Type = "call.busy"
		busy.SentAt = h.now().UnixMilli()
		h.enqueueNotification(notification{callee: payload.CalleeID, event: busy, cancel: true})
		return ErrCalleeBusy
	}
	now := h.now()
	if h.history != nil {
		if err := h.history.StartCall(event.CallID, sender, payload.CalleeID, now); err != nil {
			return err
		}
	}
	c := &call{
		id:                  event.CallID,
		caller:              sender,
		callee:              payload.CalleeID,
		callerDeviceID:      senderDeviceID,
		seen:                map[string]struct{}{},
		offlineSince:        map[string]time.Time{},
		nextSeq:             1,
		startedAt:           now,
		state:               callRinging,
		supportsCrossCall:   payload.SupportsCrossCall,
		callerSupportsVideo: payload.SupportsVideo,
	}
	c.remember(event.ID)
	h.calls[event.CallID] = c
	h.activeByUser[sender] = event.CallID
	h.activeByUser[payload.CalleeID] = event.CallID

	incoming := event
	incoming.Type = "call.incoming"
	incoming.SentAt = now.UnixMilli()
	delivered := h.next(c, incoming, payload.CalleeID)
	h.deliver(payload.CalleeID, delivered)
	h.enqueueNotification(notification{caller: sender, callee: payload.CalleeID, event: delivered})
	return nil
}

func (h *Hub) acceptCrossed(c *call, calleeDeviceID string, source protocol.Event, calleeSupportsVideo bool) error {
	if h.history != nil {
		if err := h.history.MarkCallAccepted(c.id); err != nil {
			return err
		}
	}
	c.remember(source.ID)
	c.calleeSupportsVideo = calleeSupportsVideo
	c.calleeDeviceID = calleeDeviceID
	h.callAliases[source.CallID] = c.id
	c.aliases = append(c.aliases, source.CallID)
	c.state = callActive
	for _, participant := range []string{c.caller, c.callee} {
		if !h.hasOnlineCallClient(c, participant) {
			c.offlineSince[participant] = h.now()
		}
	}
	accept := source
	accept.CallID = c.id
	accept.Type = "call.accept"
	accept.SentAt = h.now().UnixMilli()
	accept.Payload = json.RawMessage(`{"crossed":true,"offerer":true}`)
	var callerEvent DeliveredEvent
	if c.devicesBound() {
		callerEvent = h.nextDevice(c, accept, c.caller, c.callerDeviceID)
		h.deliverDevice(c.caller, c.callerDeviceID, callerEvent)
	} else {
		callerEvent = h.next(c, accept, c.caller)
		h.deliver(c.caller, callerEvent)
	}
	accept.Payload = json.RawMessage(`{"crossed":true,"offerer":false}`)
	var calleeEvent DeliveredEvent
	if c.devicesBound() {
		calleeEvent = h.nextDevice(c, accept, c.callee, c.calleeDeviceID)
		h.deliverDevice(c.callee, c.calleeDeviceID, calleeEvent)
	} else {
		calleeEvent = h.next(c, accept, c.callee)
		h.deliver(c.callee, calleeEvent)
	}
	h.deliverICEConfig(c, "")
	h.enqueueNotification(notification{callee: c.callee, event: calleeEvent, cancel: true})
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
				for _, alias := range c.aliases {
					delete(h.callAliases, alias)
				}
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
				h.finishHistory(c, disconnectedOutcome(c), now)
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
		h.finishHistory(c, outcomeForEvent(c, event.Type), now)
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
		return clientError{
			message:    "too many ICE events",
			code:       iceRateLimitCode,
			retryAfter: time.Minute - now.Sub(c.iceWindowAt),
		}
	}
	c.iceCount++
	return nil
}

func (h *Hub) checkRestartRate(c *call) error {
	now := h.now()
	if !c.lastRestart.IsZero() && now.Sub(c.lastRestart) < RestartMinInterval {
		return clientError{
			message:    "ICE restart requested too often",
			code:       iceRestartRateLimitCode,
			retryAfter: RestartMinInterval - now.Sub(c.lastRestart),
		}
	}
	c.lastRestart = now
	return nil
}

func (h *Hub) checkRestartRequestRate(c *call) error {
	now := h.now()
	if !c.lastRestartRequest.IsZero() && now.Sub(c.lastRestartRequest) < RestartRequestMinInterval {
		return clientError{
			message:    "ICE restart request sent too often",
			code:       iceRestartRequestRateLimitCode,
			retryAfter: RestartRequestMinInterval - now.Sub(c.lastRestartRequest),
		}
	}
	c.lastRestartRequest = now
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

func (h *Hub) nextDevice(c *call, event protocol.Event, recipient, deviceID string) DeliveredEvent {
	delivered := DeliveredEvent{Event: event, Seq: c.nextSeq}
	c.nextSeq++
	c.appendDeviceReplay(recipient, deviceID, delivered)
	return delivered
}

func (h *Hub) callByID(callID string) (*call, bool) {
	if c, ok := h.calls[callID]; ok {
		return c, true
	}
	canonicalID, ok := h.callAliases[callID]
	if !ok {
		return nil, false
	}
	c, ok := h.calls[canonicalID]
	return c, ok
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

func (h *Hub) deliverDevice(user, deviceID string, event DeliveredEvent) {
	if deviceID == "" {
		h.deliver(user, event)
		return
	}
	for client := range h.clients[user] {
		if client.deviceID != deviceID {
			continue
		}
		select {
		case client.inbox <- event:
		default:
			h.disconnectLocked(client)
		}
	}
}

func isDeviceBoundEvent(eventType string) bool {
	switch eventType {
	case "call.accept", "rtc.offer", "rtc.answer", "rtc.ice", "rtc.restart", "rtc.restart.request", "rtc.video":
		return true
	default:
		return false
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

func (h *Hub) finishHistory(c *call, outcome state.CallOutcome, endedAt time.Time) {
	if h.history != nil {
		_ = h.history.FinishCall(c.id, outcome, endedAt)
	}
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
	case "call.end", "call.connected", "rtc.ice", "rtc.video":
		return nil
	case "rtc.offer", "rtc.restart":
		if sender != c.caller {
			return errors.New("only caller can send this event")
		}
		return nil
	case "rtc.restart.request":
		if sender != c.callee {
			return errors.New("only callee can request ICE restart")
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
