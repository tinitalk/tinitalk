package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

var ErrInvalidRegistration = errors.New("invalid FCM registration token")

const (
	RequestTimeout        = 5 * time.Second
	callNotificationTTL   = 30 * time.Second
	missedNotificationTTL = 28 * 24 * time.Hour
)

type PushTargetStore interface {
	PushTargetsForUser(login string) ([]state.Device, error)
	ContactDisplayName(owner, contact string) (string, error)
	DisablePushTarget(target state.PushTarget) error
}

type Sender interface {
	Send(request WakeRequest) error
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

type FCMNotifier struct {
	store   PushTargetStore
	sender  Sender
	project string
}

func NewFCMNotifier(store PushTargetStore, sender Sender, project string) *FCMNotifier {
	return &FCMNotifier{store: store, sender: sender, project: project}
}

func (n *FCMNotifier) IncomingCall(caller, callee string, event signaling.DeliveredEvent) {
	name, err := n.store.ContactDisplayName(callee, caller)
	if err != nil || name == "" {
		name = caller
	}
	n.send(callee, WakeMessage(n.project, state.PushTarget{}, event, caller, name, callNotificationTTL))
}

func (n *FCMNotifier) CancelCall(callee string, event signaling.DeliveredEvent) {
	ttl := callNotificationTTL
	if event.Type == "call.cancel" || event.Type == "call.expire" || event.Type == "call.busy" {
		ttl = missedNotificationTTL
	}
	n.send(callee, CancelMessage(n.project, state.PushTarget{}, event, ttl))
}

func (n *FCMNotifier) SessionReplaced(login, revokedSessionID string, devices []state.Device) {
	for _, device := range devices {
		if device.PushTarget.Value == "" {
			continue
		}
		_ = n.sender.Send(SessionReplacedMessage(
			device.PushTarget,
			login,
			revokedSessionID,
			device.DeviceID,
		))
	}
}

func (n *FCMNotifier) send(callee string, request WakeRequest) {
	if request.suppress {
		return
	}
	targets, err := n.store.PushTargetsForUser(callee)
	if err != nil {
		return
	}
	for _, device := range targets {
		targetSessionID, hasSessionTarget := request.Message.Data["target_session_id"]
		targetDeviceID := request.Message.Data["target_device_id"]
		if hasSessionTarget && targetSessionID != "" && (targetDeviceID == "" || device.DeviceID != targetDeviceID) {
			continue
		}
		targeted := request
		targeted.Message.Data = cloneData(request.Message.Data)
		if hasSessionTarget {
			targeted.Message.Data["target_login"] = callee
			if targetSessionID == "" {
				targeted.Message.Data["target_device_id"] = device.DeviceID
			}
		}
		setPushTarget(&targeted, device.PushTarget)
		err := n.sender.Send(targeted)
		if errors.Is(err, ErrInvalidRegistration) {
			_ = n.store.DisablePushTarget(device.PushTarget)
		}
	}
}

type WakeRequest struct {
	Message struct {
		Token   string            `json:"token,omitempty"`
		FID     string            `json:"fid,omitempty"`
		Data    map[string]string `json:"data"`
		Android struct {
			Priority string `json:"priority"`
			TTL      string `json:"ttl"`
		} `json:"android"`
	} `json:"message"`
	suppress bool
}

func WakeMessage(_ string, target state.PushTarget, event signaling.DeliveredEvent, callerLogin, caller string, ttl time.Duration) WakeRequest {
	var request WakeRequest
	setPushTarget(&request, target)
	request.Message.Data = map[string]string{
		"type":         "incoming_call",
		"call_id":      event.CallID,
		"caller":       caller,
		"caller_login": callerLogin,
		"last_seq":     strconv.FormatUint(event.Seq, 10),
		"expires_at":   time.UnixMilli(event.SentAt).Add(ttl).UTC().Format(time.RFC3339),
	}
	request.suppress = event.TargetResolutionFailed
	if event.TargetSessionKnown {
		request.Message.Data["target_session_id"] = event.TargetSessionID
		if event.TargetSessionID != "" {
			request.Message.Data["target_device_id"] = event.TargetDeviceID
		}
	}
	request.Message.Android.Priority = "HIGH"
	request.Message.Android.TTL = fcmTTL(ttl)
	return request
}

func CancelMessage(_ string, target state.PushTarget, event signaling.DeliveredEvent, ttl time.Duration) WakeRequest {
	var request WakeRequest
	setPushTarget(&request, target)
	request.Message.Data = map[string]string{
		"type":       "call_cancel",
		"call_id":    event.CallID,
		"call_event": event.Type,
	}
	request.suppress = event.TargetResolutionFailed
	if event.TargetSessionKnown {
		request.Message.Data["target_session_id"] = event.TargetSessionID
		if event.TargetSessionID != "" {
			request.Message.Data["target_device_id"] = event.TargetDeviceID
		}
	}
	request.Message.Android.Priority = "HIGH"
	request.Message.Android.TTL = fcmTTL(ttl)
	return request
}

func SessionReplacedMessage(target state.PushTarget, login, revokedSessionID, revokedDeviceID string) WakeRequest {
	var request WakeRequest
	setPushTarget(&request, target)
	request.Message.Data = map[string]string{
		"type":               "session_replaced",
		"login":              login,
		"revoked_session_id": revokedSessionID,
		"revoked_device_id":  revokedDeviceID,
	}
	request.Message.Android.Priority = "HIGH"
	request.Message.Android.TTL = fcmTTL(missedNotificationTTL)
	return request
}

func setPushTarget(request *WakeRequest, target state.PushTarget) {
	request.Message.Token = ""
	request.Message.FID = ""
	if target.Kind == state.KindFID {
		request.Message.FID = target.Value
		return
	}
	if target.Kind == state.KindToken {
		request.Message.Token = target.Value
	}
}

func fcmTTL(ttl time.Duration) string {
	return strconv.FormatInt(int64(ttl/time.Second), 10) + "s"
}

func cloneData(source map[string]string) map[string]string {
	cloned := make(map[string]string, len(source)+2)
	for key, value := range source {
		cloned[key] = value
	}
	return cloned
}

type HTTPv1Sender struct {
	Client      *http.Client
	Endpoint    string
	BearerToken func() (string, error)
}

func (s HTTPv1Sender) Send(request WakeRequest) error {
	token, err := s.BearerToken()
	if err != nil {
		return err
	}
	raw, err := json.Marshal(request)
	if err != nil {
		return err
	}
	client := s.Client
	if client == nil {
		client = http.DefaultClient
	}
	req, err := http.NewRequest(http.MethodPost, s.Endpoint, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 200 && resp.StatusCode <= 299 {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return fmt.Errorf("FCM send failed: HTTP %d", resp.StatusCode)
	}
	var failure struct {
		Error struct {
			Status  string `json:"status"`
			Details []struct {
				Type      string `json:"@type"`
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	_ = json.Unmarshal(body, &failure)
	if request.Message.FID != "" && resp.StatusCode == http.StatusNotFound && failure.Error.Status == "NOT_FOUND" {
		return ErrInvalidRegistration
	}
	for _, detail := range failure.Error.Details {
		if detail.Type != "type.googleapis.com/google.firebase.fcm.v1.FcmError" {
			continue
		}
		if request.Message.Token != "" && detail.ErrorCode == "UNREGISTERED" {
			return ErrInvalidRegistration
		}
	}
	if failure.Error.Status == "" {
		return fmt.Errorf("FCM send failed: HTTP %d", resp.StatusCode)
	}
	return fmt.Errorf("FCM send failed: HTTP %d (%s)", resp.StatusCode, failure.Error.Status)
}

func BearerTokenFromServiceAccount(ctx context.Context, serviceAccount []byte) (func() (string, error), error) {
	ctx = context.WithValue(ctx, oauth2.HTTPClient, &http.Client{Timeout: RequestTimeout})
	config, err := google.JWTConfigFromJSON(serviceAccount, "https://www.googleapis.com/auth/firebase.messaging")
	if err != nil {
		return nil, err
	}
	source := config.TokenSource(ctx)
	return func() (string, error) {
		token, err := source.Token()
		if err != nil {
			return "", err
		}
		return token.AccessToken, nil
	}, nil
}

func ProjectIDFromServiceAccount(serviceAccount []byte) (string, error) {
	var data struct {
		ProjectID string `json:"project_id"`
	}
	if err := json.Unmarshal(serviceAccount, &data); err != nil {
		return "", err
	}
	if data.ProjectID == "" {
		return "", errors.New("project_id is required")
	}
	return data.ProjectID, nil
}
