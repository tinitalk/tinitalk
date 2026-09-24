package httpapi

import (
	"net/http"
	"time"
)

func (s *Server) activeCall(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.hub == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	deviceID := r.Header.Get(deviceIDHeader)
	sessionID := ""
	if session, managed := currentSession(r); managed {
		deviceID = session.DeviceID
		sessionID = session.SessionID
	}
	snapshot, err := s.hub.ActiveCallSnapshotForDevice(currentUser(r).Login, deviceID, sessionID)
	if err != nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	response := activeCallResponse{CallID: snapshot.CallID}
	if r.URL.Query().Get("call_waiting") == "1" {
		response.IncomingCalls = make([]activeIncomingResponse, 0, len(snapshot.IncomingCalls))
		for _, incoming := range snapshot.IncomingCalls {
			response.IncomingCalls = append(response.IncomingCalls, activeIncomingResponse{
				CallID: incoming.CallID, CallerLogin: incoming.CallerLogin, Waiting: incoming.Waiting,
				StartedAt: incoming.StartedAt.UTC().Format(time.RFC3339Nano), ExpiresAt: incoming.ExpiresAt.UTC().Format(time.RFC3339Nano), LastSeq: incoming.LastSeq,
			})
		}
	}
	if snapshot.Incoming != nil {
		response.Incoming = &activeIncomingResponse{
			CallID:      snapshot.CallID,
			CallerLogin: snapshot.Incoming.CallerLogin,
			StartedAt:   snapshot.Incoming.StartedAt.UTC().Format(time.RFC3339Nano),
			ExpiresAt:   snapshot.Incoming.ExpiresAt.UTC().Format(time.RFC3339Nano),
			LastSeq:     snapshot.Incoming.LastSeq,
		}
	}
	writeJSON(w, response)
}

type activeCallResponse struct {
	CallID        string                   `json:"call_id"`
	Incoming      *activeIncomingResponse  `json:"incoming,omitempty"`
	IncomingCalls []activeIncomingResponse `json:"incoming_calls,omitempty"`
}

type activeIncomingResponse struct {
	Waiting     bool   `json:"waiting,omitempty"`
	CallID      string `json:"call_id"`
	CallerLogin string `json:"caller_login"`
	StartedAt   string `json:"started_at"`
	ExpiresAt   string `json:"expires_at"`
	LastSeq     uint64 `json:"last_seq"`
}
