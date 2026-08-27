package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
)

const deviceIDHeader = "X-TiniTalk-Device-ID"

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
	user := currentUser(r).Login
	client, err := s.hub.ConnectDeviceChecked(user, r.Header.Get(deviceIDHeader))
	if err != nil {
		http.Error(w, err.Error(), http.StatusTooManyRequests)
		return
	}
	conn, err := websocket.Upgrade(w, r, nil, protocol.MaxEventBytes, protocol.MaxEventBytes)
	if err != nil {
		s.hub.Disconnect(client)
		return
	}
	defer conn.Close()
	defer s.hub.Disconnect(client)
	if !s.hub.Connected(client) {
		return
	}
	var writeMu sync.Mutex
	writeJSON := func(value any) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		if err := conn.SetWriteDeadline(time.Now().Add(s.socketTiming.writeTimeout)); err != nil {
			return err
		}
		return conn.WriteJSON(value)
	}
	done := make(chan struct{})
	defer close(done)
	go func() {
		for event := range client.Events() {
			if err := writeJSON(event); err != nil {
				_ = conn.Close()
				return
			}
		}
		_ = conn.Close()
	}()

	conn.SetReadLimit(protocol.MaxEventBytes)
	if err := conn.SetReadDeadline(time.Now().Add(s.socketTiming.pongTimeout)); err != nil {
		return
	}
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(s.socketTiming.pongTimeout))
	})
	go func() {
		ticker := time.NewTicker(s.socketTiming.pingInterval)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				writeMu.Lock()
				err := conn.SetWriteDeadline(time.Now().Add(s.socketTiming.writeTimeout))
				if err == nil {
					err = conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(s.socketTiming.writeTimeout))
				}
				writeMu.Unlock()
				if err != nil {
					_ = conn.Close()
					return
				}
			}
		}
	}()
	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			return
		}
		event, err := protocol.Decode(raw)
		if err != nil {
			_ = writeJSON(map[string]string{"error": err.Error()})
			continue
		}
		if err := s.hub.Handle(user, event); err != nil {
			failure := map[string]any{"error": err.Error(), "call_id": event.CallID, "event_id": event.ID}
			if errors.Is(err, signaling.ErrCalleeBusy) {
				failure["code"] = "busy"
			}
			var clientError signaling.ClientError
			if errors.As(err, &clientError) {
				failure["code"] = clientError.Code()
				retryAfter := clientError.RetryAfter()
				retryAfterMillis := max(int64(1), (retryAfter.Nanoseconds()+int64(time.Millisecond)-1)/int64(time.Millisecond))
				failure["retry_after_ms"] = retryAfterMillis
			}
			_ = writeJSON(failure)
			continue
		}
		select {
		case <-done:
			return
		default:
		}
	}
}
