package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"

	"tinitalk/internal/state"
	"tinitalk/internal/webpush"
)

type sessionRequest struct {
	DeviceID            string          `json:"device_id"`
	WebPushSubscription json.RawMessage `json:"webpush_subscription"`
	ConfigID            json.RawMessage `json:"config_id"`
}

func (s *Server) session(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var request sessionRequest
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	target, ok := request.pushTarget()
	if r.URL.Path == "/api/browser/session" && request.DeviceID != "" && request.WebPushSubscription == nil && request.ConfigID == nil {
		ok = true
	}
	if !ok {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if target != nil && target.ConfigID != s.options.WebPushConfigID {
		http.Error(w, "stale WebPush configuration", http.StatusConflict)
		return
	}

	login := currentUser(r).Login
	_, presentedToken, credentialsPresent := r.BasicAuth()
	s.sessionClaimMu.Lock()
	if !credentialsPresent {
		s.sessionClaimMu.Unlock()
		w.Header().Set("WWW-Authenticate", `Basic realm="tinitalk"`)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	claim, err := s.db.ClaimSessionWithToken(login, presentedToken, request.DeviceID, target)
	if err != nil {
		s.sessionClaimMu.Unlock()
		if errors.Is(err, state.ErrInvalidCredentials) {
			w.Header().Set("WWW-Authenticate", `Basic realm="tinitalk"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		http.Error(w, "session unavailable", http.StatusInternalServerError)
		return
	}
	var revokedSessionID string
	var revokedDevices []state.Device
	if claim.Changed {
		if s.hub != nil {
			s.hub.ReplaceSession(login, claim.Current.SessionID)
		}
		if claim.Previous != nil {
			revokedSessionID = claim.Previous.SessionID
		}
		revokedDevices = append([]state.Device(nil), claim.RevokedDevices...)
	}
	if s.sessionNotify != nil && len(revokedDevices) > 0 {
		notifier := s.sessionNotify
		go notifier.SessionReplaced(login, revokedSessionID, revokedDevices)
	}
	writeJSON(w, struct {
		SessionID string `json:"session_id"`
	}{SessionID: claim.Current.SessionID})
	s.sessionClaimMu.Unlock()
}

func (request sessionRequest) pushTarget() (*state.PushTarget, bool) {
	if request.DeviceID == "" {
		return nil, false
	}
	webPushPresent := request.WebPushSubscription != nil
	configID, configPresent, configValid := requestString(request.ConfigID)
	if webPushPresent {
		if !configPresent || !configValid {
			return nil, false
		}
		_, canonical, err := webpush.ParseSubscription(request.WebPushSubscription)
		if err != nil {
			return nil, false
		}
		return &state.PushTarget{Subscription: canonical, ConfigID: configID}, true
	}
	return nil, false
}

func requestString(raw json.RawMessage) (value string, present, valid bool) {
	if raw == nil {
		return "", false, true
	}
	if err := json.Unmarshal(raw, &value); err != nil || value == "" {
		return "", true, false
	}
	return value, true, true
}
