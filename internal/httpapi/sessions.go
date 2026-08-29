package httpapi

import (
	"encoding/json"
	"net/http"

	"tinitalk/internal/state"
)

type sessionRequest struct {
	DeviceID string `json:"device_id"`
}

func (s *Server) session(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var request sessionRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil || request.DeviceID == "" {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	login := currentUser(r).Login
	s.sessionClaimMu.Lock()
	claim, err := s.db.ClaimSession(login, request.DeviceID)
	if err != nil {
		s.sessionClaimMu.Unlock()
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
