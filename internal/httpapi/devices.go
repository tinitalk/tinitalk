package httpapi

import (
	"encoding/json"
	"net/http"

	"github.com/gorilla/websocket"
	"tinitalk/internal/protocol"
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
	if s.hub == nil {
		http.Error(w, "websocket endpoint is not active yet", http.StatusUpgradeRequired)
		return
	}
	conn, err := websocket.Upgrade(w, r, nil, protocol.MaxEventBytes, protocol.MaxEventBytes)
	if err != nil {
		return
	}
	defer conn.Close()

	client := s.hub.Connect(currentUser(r).Login)
	defer s.hub.Disconnect(client)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for event := range client.Events() {
			if err := conn.WriteJSON(event); err != nil {
				return
			}
		}
	}()

	conn.SetReadLimit(protocol.MaxEventBytes)
	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			return
		}
		event, err := protocol.Decode(raw)
		if err != nil {
			_ = conn.WriteJSON(map[string]string{"error": err.Error()})
			continue
		}
		if err := s.hub.Handle(currentUser(r).Login, event); err != nil {
			_ = conn.WriteJSON(map[string]string{"error": err.Error()})
			continue
		}
		select {
		case <-done:
			return
		default:
		}
	}
}
