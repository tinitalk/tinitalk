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

type DeviceToken struct {
	DeviceID string
	Token    string
}

type TokenStore interface {
	TokensForUser(login string) ([]DeviceToken, error)
	ContactDisplayName(owner, contact string) (string, error)
	DisableToken(token string) error
}

type Sender interface {
	Send(request WakeRequest) error
}

type DBTokenStore struct {
	DB *state.DB
}

func (s DBTokenStore) TokensForUser(login string) ([]DeviceToken, error) {
	devices, err := s.DB.TokensForUser(login)
	if err != nil {
		return nil, err
	}
	tokens := make([]DeviceToken, 0, len(devices))
	for _, device := range devices {
		tokens = append(tokens, DeviceToken{DeviceID: device.DeviceID, Token: device.FCMToken})
	}
	return tokens, nil
}

func (s DBTokenStore) DisableToken(token string) error {
	return s.DB.DisableToken(token)
}

func (s DBTokenStore) ContactDisplayName(owner, contact string) (string, error) {
	return s.DB.ContactDisplayName(owner, contact)
}

type FCMNotifier struct {
	store   TokenStore
	sender  Sender
	project string
}

func NewFCMNotifier(store TokenStore, sender Sender, project string) *FCMNotifier {
	return &FCMNotifier{store: store, sender: sender, project: project}
}

func (n *FCMNotifier) IncomingCall(caller, callee string, event signaling.DeliveredEvent) {
	name, err := n.store.ContactDisplayName(callee, caller)
	if err != nil || name == "" {
		name = caller
	}
	n.send(callee, WakeMessage(n.project, "", event, caller, name, callNotificationTTL))
}

func (n *FCMNotifier) CancelCall(callee string, event signaling.DeliveredEvent) {
	ttl := callNotificationTTL
	if event.Type == "call.cancel" || event.Type == "call.expire" || event.Type == "call.busy" {
		ttl = missedNotificationTTL
	}
	n.send(callee, CancelMessage(n.project, "", event, ttl))
}

func (n *FCMNotifier) SessionReplaced(login, revokedSessionID string, devices []state.Device) {
	for _, device := range devices {
		if device.FCMToken == "" {
			continue
		}
		_ = n.sender.Send(SessionReplacedMessage(
			device.FCMToken,
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
	tokens, err := n.store.TokensForUser(callee)
	if err != nil {
		return
	}
	for _, token := range tokens {
		targetSessionID, hasSessionTarget := request.Message.Data["target_session_id"]
		targetDeviceID := request.Message.Data["target_device_id"]
		if hasSessionTarget && targetSessionID != "" && (targetDeviceID == "" || token.DeviceID != targetDeviceID) {
			continue
		}
		targeted := request
		targeted.Message.Data = cloneData(request.Message.Data)
		if hasSessionTarget {
			targeted.Message.Data["target_login"] = callee
			if targetSessionID == "" {
				targeted.Message.Data["target_device_id"] = token.DeviceID
			}
		}
		targeted.Message.Token = token.Token
		err := n.sender.Send(targeted)
		if errors.Is(err, ErrInvalidRegistration) {
			_ = n.store.DisableToken(token.Token)
		}
	}
}

type WakeRequest struct {
	Message struct {
		Token   string            `json:"token"`
		Data    map[string]string `json:"data"`
		Android struct {
			Priority string `json:"priority"`
			TTL      string `json:"ttl"`
		} `json:"android"`
	} `json:"message"`
	suppress bool
}

func WakeMessage(_ string, token string, event signaling.DeliveredEvent, callerLogin, caller string, ttl time.Duration) WakeRequest {
	var request WakeRequest
	request.Message.Token = token
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

func CancelMessage(_ string, token string, event signaling.DeliveredEvent, ttl time.Duration) WakeRequest {
	var request WakeRequest
	request.Message.Token = token
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

func SessionReplacedMessage(token, login, revokedSessionID, revokedDeviceID string) WakeRequest {
	var request WakeRequest
	request.Message.Token = token
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
	for _, detail := range failure.Error.Details {
		if detail.Type != "type.googleapis.com/google.firebase.fcm.v1.FcmError" {
			continue
		}
		if detail.ErrorCode == "INVALID_ARGUMENT" || detail.ErrorCode == "UNREGISTERED" {
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
