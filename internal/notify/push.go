package notify

import (
	"errors"
	"log"
	"strconv"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
	"tinitalk/internal/webpush"
)

var ErrInvalidPushSubscription = errors.New("invalid WebPush subscription")
var ErrTemporaryPushDelivery = errors.New("temporary WebPush delivery failure")

const (
	RequestTimeout        = 5 * time.Second
	MaxConcurrentWebPush  = 8
	callNotificationTTL   = time.Duration(protocol.RingTimeoutSecs) * time.Second
	missedNotificationTTL = 28 * 24 * time.Hour
)

var defaultWebPushRetryDelays = []time.Duration{250 * time.Millisecond}

type PushTargetStore interface {
	PushTargetsForUser(login string) ([]state.Device, error)
	ContactDisplayName(owner, contact string) (string, error)
	DisablePushTarget(target state.PushTarget) error
}

type WebPushSender interface {
	Send(request WebPushRequest) error
}

type IncomingCallGate interface {
	WaitIncomingPush(user, deviceID, sessionID, callID string) bool
	IncomingPushPending(user, sessionID, callID string) bool
}

type DBPushTargetStore struct {
	DB *state.DB
}

func (s DBPushTargetStore) PushTargetsForUser(login string) ([]state.Device, error) {
	return s.DB.PushTargetsForUser(login)
}

func (s DBPushTargetStore) DisablePushTarget(target state.PushTarget) error {
	return s.DB.DisablePushTarget(target)
}

func (s DBPushTargetStore) ContactDisplayName(owner, contact string) (string, error) {
	return s.DB.ContactDisplayName(owner, contact)
}

type PushNotifier struct {
	store        PushTargetStore
	sender       WebPushSender
	retryDelays  []time.Duration
	sendSlots    chan struct{}
	incomingGate IncomingCallGate
}

// SetIncomingCallGate is configured before serving requests.
func (n *PushNotifier) SetIncomingCallGate(gate IncomingCallGate) { n.incomingGate = gate }

func NewPushNotifier(store PushTargetStore, sender WebPushSender) *PushNotifier {
	return &PushNotifier{
		store:       store,
		sender:      sender,
		retryDelays: append([]time.Duration(nil), defaultWebPushRetryDelays...),
		sendSlots:   make(chan struct{}, MaxConcurrentWebPush),
	}
}

func (n *PushNotifier) IncomingCall(caller, callee string, event signaling.DeliveredEvent) {
	name, err := n.store.ContactDisplayName(callee, caller)
	if err != nil || name == "" {
		name = caller
	}
	n.send(callee, WakeMessage(event, caller, name, callNotificationTTL))
}

func (n *PushNotifier) CancelCall(callee string, event signaling.DeliveredEvent) {
	ttl := callNotificationTTL
	if event.Type == "call.cancel" || event.Type == "call.expire" || event.Type == "call.busy" {
		ttl = missedNotificationTTL
	}
	n.send(callee, CancelMessage(event, ttl))
}

func (n *PushNotifier) ContactChanged(recipient, contact string, session state.AccountSession) {
	n.send(recipient, PushMessage{
		Data: map[string]string{
			"type":              "contact_changed",
			"contact_login":     contact,
			"target_session_id": session.SessionID,
			"target_device_id":  session.DeviceID,
		},
		ttl: missedNotificationTTL,
	})
}

func (n *PushNotifier) SessionReplaced(login, revokedSessionID string, devices []state.Device) {
	for _, device := range devices {
		if device.PushTarget.Subscription == "" {
			continue
		}
		message := SessionReplacedMessage(login, revokedSessionID, device.DeviceID)
		err := n.sendTarget(device.PushTarget, message)
		if errors.Is(err, ErrInvalidPushSubscription) {
			_ = n.store.DisablePushTarget(device.PushTarget)
		} else if err != nil {
			log.Printf("WebPush delivery failed after retries (type=%s)", message.Data["type"])
		}
	}
}

func (n *PushNotifier) send(callee string, message PushMessage) {
	if message.suppress {
		return
	}
	targets, err := n.store.PushTargetsForUser(callee)
	if err != nil {
		return
	}
	for _, device := range targets {
		targetSessionID, hasSessionTarget := message.Data["target_session_id"]
		targetDeviceID := message.Data["target_device_id"]
		if hasSessionTarget && targetSessionID != "" && (targetDeviceID == "" || device.DeviceID != targetDeviceID) {
			continue
		}
		targeted := message
		targeted.Data = cloneData(message.Data)
		if hasSessionTarget {
			targeted.Data["target_login"] = callee
			if targetSessionID == "" {
				targeted.Data["target_device_id"] = device.DeviceID
			}
		}
		if n.incomingGate != nil && targeted.Data["type"] == "incoming_call" &&
			(webpush.IsBrowserSubscription(device.PushTarget.Subscription) || webpush.IsAppleSubscription(device.PushTarget.Subscription)) {
			// Do not hold up the hub's cancellation queue or other push targets.
			go n.sendBrowserIncoming(callee, device, targeted)
		} else {
			n.deliverTarget(device.PushTarget, targeted)
		}
	}
}

func (n *PushNotifier) sendBrowserIncoming(user string, device state.Device, message PushMessage) {
	if !n.incomingGate.WaitIncomingPush(user, device.DeviceID, message.Data["target_session_id"], message.Data["call_id"]) {
		return
	}
	message.stillIncoming = func() bool {
		return n.incomingGate.IncomingPushPending(user, message.Data["target_session_id"], message.Data["call_id"])
	}
	n.deliverTarget(device.PushTarget, message)
}

func (n *PushNotifier) deliverTarget(target state.PushTarget, message PushMessage) {
	err := n.sendTarget(target, message)
	if errors.Is(err, ErrInvalidPushSubscription) {
		_ = n.store.DisablePushTarget(target)
	} else if err != nil {
		log.Printf("WebPush delivery failed after retries (type=%s)", message.Data["type"])
	}
}

func (n *PushNotifier) sendTarget(target state.PushTarget, message PushMessage) error {
	// Browser push must have a visible result. Contacts are refreshed on opening
	// the client; do not send the native client's silent sync trigger to the web.
	apple := webpush.IsAppleSubscription(target.Subscription)
	if apple && message.Data["type"] != "incoming_call" && message.Data["type"] != "session_replaced" {
		return nil
	}
	if webpush.IsBrowserSubscription(target.Subscription) && message.Data["type"] == "contact_changed" {
		return nil
	}
	n.sendSlots <- struct{}{}
	defer func() { <-n.sendSlots }()

	if n.sender == nil {
		return errors.New("WebPush sender is unavailable")
	}
	request := WebPushRequest{
		Subscription: target.Subscription,
		Data:         cloneData(message.Data),
		TTL:          message.ttl,
	}
	send := func() error {
		if message.stillIncoming != nil {
			if !message.stillIncoming() {
				return nil
			}
			// Waiting for capacity and retries cannot extend the ringing window.
			if expires, err := time.Parse(time.RFC3339Nano, message.Data["expires_at"]); err == nil {
				request.TTL = min(message.ttl, time.Until(expires))
				if request.TTL <= 0 {
					return nil
				}
			}
		}
		return n.sender.Send(request)
	}
	err := send()
	for _, delay := range n.retryDelays {
		if !errors.Is(err, ErrTemporaryPushDelivery) {
			break
		}
		time.Sleep(delay)
		err = send()
	}
	return err
}

type WebPushRequest struct {
	Subscription string
	Data         map[string]string
	TTL          time.Duration
}

type PushMessage struct {
	Data          map[string]string
	suppress      bool
	ttl           time.Duration
	stillIncoming func() bool
}

func WakeMessage(event signaling.DeliveredEvent, callerLogin, caller string, ttl time.Duration) PushMessage {
	message := PushMessage{
		Data: map[string]string{
			"type":         "incoming_call",
			"call_id":      event.CallID,
			"caller":       caller,
			"caller_login": callerLogin,
			"last_seq":     strconv.FormatUint(event.Seq, 10),
			"started_at":   time.UnixMilli(event.SentAt).UTC().Format(time.RFC3339Nano),
			"expires_at":   time.UnixMilli(event.SentAt).Add(ttl).UTC().Format(time.RFC3339Nano),
		},
		suppress: event.TargetResolutionFailed,
		ttl:      ttl,
	}
	addSessionTarget(message.Data, event)
	return message
}

func CancelMessage(event signaling.DeliveredEvent, ttl time.Duration) PushMessage {
	message := PushMessage{
		Data: map[string]string{
			"type":       "call_cancel",
			"call_id":    event.CallID,
			"call_event": event.Type,
		},
		suppress: event.TargetResolutionFailed,
		ttl:      ttl,
	}
	addSessionTarget(message.Data, event)
	return message
}

func addSessionTarget(data map[string]string, event signaling.DeliveredEvent) {
	if !event.TargetSessionKnown {
		return
	}
	data["target_session_id"] = event.TargetSessionID
	if event.TargetSessionID != "" {
		data["target_device_id"] = event.TargetDeviceID
	}
}

func SessionReplacedMessage(login, revokedSessionID, revokedDeviceID string) PushMessage {
	return PushMessage{
		Data: map[string]string{
			"type":               "session_replaced",
			"login":              login,
			"revoked_session_id": revokedSessionID,
			"revoked_device_id":  revokedDeviceID,
		},
		ttl: missedNotificationTTL,
	}
}

func cloneData(source map[string]string) map[string]string {
	cloned := make(map[string]string, len(source)+2)
	for key, value := range source {
		cloned[key] = value
	}
	return cloned
}
