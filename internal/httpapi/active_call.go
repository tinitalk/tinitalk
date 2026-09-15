package httpapi

import "net/http"

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
	if session, managed := currentSession(r); managed {
		deviceID = session.DeviceID
	}
	callID, err := s.hub.ActiveCallForDevice(currentUser(r).Login, deviceID)
	if err != nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	writeJSON(w, struct {
		CallID string `json:"call_id"`
	}{CallID: callID})
}
