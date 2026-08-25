package httpapi

import (
	"encoding/json"
	"net/http"
)

type deviceRequest struct {
	DeviceID string `json:"device_id"`
	FCMToken string `json:"fcm_token"`
}

func (s *Server) device(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req deviceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if req.DeviceID == "" || req.FCMToken == "" {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if err := s.db.UpsertDevice(currentUser(r).Login, req.DeviceID, req.FCMToken); err != nil {
		http.Error(w, "device unavailable", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) socket(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	http.Error(w, "websocket endpoint is not active yet", http.StatusUpgradeRequired)
}
