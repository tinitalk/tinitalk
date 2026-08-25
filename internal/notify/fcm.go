package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"golang.org/x/oauth2/google"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

var ErrInvalidRegistration = errors.New("invalid FCM registration token")

type DeviceToken struct {
	DeviceID string
	Token    string
}

type TokenStore interface {
	TokensForUser(login string) ([]DeviceToken, error)
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

type FCMNotifier struct {
	store   TokenStore
	sender  Sender
	project string
}

func NewFCMNotifier(store TokenStore, sender Sender, project string) *FCMNotifier {
	return &FCMNotifier{store: store, sender: sender, project: project}
}

func (n *FCMNotifier) IncomingCall(callee string, event signaling.DeliveredEvent) {
	tokens, err := n.store.TokensForUser(callee)
	if err != nil {
		return
	}
	for _, token := range tokens {
		err := n.sender.Send(WakeMessage(n.project, token.Token, event, 30*time.Second))
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
}

func WakeMessage(_ string, token string, event signaling.DeliveredEvent, ttl time.Duration) WakeRequest {
	var request WakeRequest
	request.Message.Token = token
	request.Message.Data = map[string]string{
		"type":       "incoming_call",
		"call_id":    event.CallID,
		"caller":     callerName(event),
		"last_seq":   strconv.FormatUint(event.Seq, 10),
		"expires_at": time.UnixMilli(event.SentAt).Add(ttl).Format(time.RFC3339),
	}
	request.Message.Android.Priority = "HIGH"
	request.Message.Android.TTL = ttl.String()
	return request
}

func callerName(event signaling.DeliveredEvent) string {
	var payload struct {
		Caller string `json:"caller"`
	}
	_ = json.Unmarshal(event.Payload, &payload)
	return payload.Caller
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
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusBadRequest {
		return ErrInvalidRegistration
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return errors.New("FCM send failed")
	}
	return nil
}

func BearerTokenFromServiceAccount(ctx context.Context, serviceAccount []byte) (func() (string, error), error) {
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
