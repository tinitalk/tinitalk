package notify

import (
	"errors"
	"log"
	"strconv"
	"time"

	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

var ErrInvalidPushSubscription = errors.New("invalid WebPush subscription")
var ErrTemporaryPushDelivery = errors.New("temporary WebPush delivery failure")

const (
	RequestTimeout        = 5 * time.Second
	MaxConcurrentWebPush  = 8
	callNotificationTTL   = 30 * time.Second
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
	store       PushTargetStore
	sender      WebPushSender
	retryDelays []time.Duration
	sendSlots   chan struct{}
}

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
		err := n.sendTarget(device.PushTarget, targeted)
		if errors.Is(err, ErrInvalidPushSubscription) {
			_ = n.store.DisablePushTarget(device.PushTarget)
		} else if err != nil {
			log.Printf("WebPush delivery failed after retries (type=%s)", targeted.Data["type"])
		}
	}
}

func (n *PushNotifier) sendTarget(target state.PushTarget, message PushMessage) error {
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
	err := n.sender.Send(request)
	for _, delay := range n.retryDelays {
		if !errors.Is(err, ErrTemporaryPushDelivery) {
			break
		}
		time.Sleep(delay)
		err = n.sender.Send(request)
	}
	return err
}

type WebPushRequest struct {
	Subscription string
	Data         map[string]string
	TTL          time.Duration
}

type PushMessage struct {
	Data     map[string]string
	suppress bool
	ttl      time.Duration
}

func WakeMessage(event signaling.DeliveredEvent, callerLogin, caller string, ttl time.Duration) PushMessage {
	message := PushMessage{
		Data: map[string]string{
			"type":         "incoming_call",
			"call_id":      event.CallID,
			"caller":       caller,
			"caller_login": callerLogin,
			"last_seq":     strconv.FormatUint(event.Seq, 10),
			"expires_at":   time.UnixMilli(event.SentAt).Add(ttl).UTC().Format(time.RFC3339),
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
