package httpapi

import (
	"errors"
	"net/http"

	"tinitalk/internal/protocol"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func (s *Server) rejectCall(w http.ResponseWriter, r *http.Request) {
	session, managed := currentSession(r)
	if !managed || r.Header.Get(deviceIDHeader) != session.DeviceID {
		writeSessionReplaced(w)
		return
	}
	callID := r.PathValue("callID")
	if err := (protocol.Event{ID: callID, CallID: callID, Type: "call.reject", Payload: []byte(`{}`)}).Validate(); err != nil {
		http.Error(w, "invalid call ID", http.StatusBadRequest)
		return
	}
	if s.hub == nil {
		http.Error(w, "signaling unavailable", http.StatusServiceUnavailable)
		return
	}
	err := s.hub.RejectIncomingCall(currentUser(r).Login, session.DeviceID, session.SessionID, callID)
	switch {
	case errors.Is(err, state.ErrSessionReplaced):
		writeSessionReplaced(w)
	case errors.Is(err, signaling.ErrNotCallCallee):
		http.Error(w, "only the callee can reject this call", http.StatusForbidden)
	case err != nil:
		http.Error(w, "could not reject call", http.StatusInternalServerError)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}
