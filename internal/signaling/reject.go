package signaling

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"tinitalk/internal/protocol"
	"tinitalk/internal/state"
)

var ErrNotCallCallee = errors.New("only the callee can reject an incoming call")

// RejectIncomingCall lets a notification action decline without connecting a
// second signaling client (which would disconnect the app's existing socket).
// Acceptance and rejection are serialized under the same call-state lock.
func (h *Hub) RejectIncomingCall(user, deviceID, sessionID, callID string) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.sessionStore == nil {
		return errors.New("session store unavailable")
	}
	// Recheck after acquiring the lock: the HTTP authentication snapshot may
	// predate a session replacement on another device.
	session, managed, err := h.sessionStore.CurrentSession(user)
	if err != nil {
		return err
	}
	if !managed || sessionID == "" || deviceID == "" || session.SessionID != sessionID || session.DeviceID != deviceID {
		return state.ErrSessionReplaced
	}
	c, ok := h.callByID(callID)
	if !ok {
		return nil
	}
	if user != c.callee {
		return ErrNotCallCallee
	}
	now := h.now()
	if c.state != callRinging || now.Sub(c.startedAt) >= time.Duration(protocol.RingTimeoutSecs)*time.Second {
		return nil // A stale notification must not end an accepted or newer call.
	}
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return err
	}
	id[6], id[8] = (id[6]&0x0f)|0x40, (id[8]&0x3f)|0x80
	event := protocol.Event{
		ID:     fmt.Sprintf("%x-%x-%x-%x-%x", id[:4], id[4:6], id[6:8], id[8:10], id[10:]),
		CallID: c.id, Type: "call.reject", SentAt: now.UnixMilli(), Payload: json.RawMessage(`{}`),
	}
	if h.history != nil {
		if err := h.history.FinishCallWithReply(c.id, state.CallOutcomeRejected, now, ""); err != nil {
			return err
		}
	}
	c.remember(event.ID)
	// Also stop a background app that is already ringing, and include the
	// terminal event in its replay if it reconnects after this request.
	delivered := h.next(c, event, c.caller, c.callee)
	h.deliver(c.caller, delivered)
	h.deliver(c.callee, delivered)
	h.enqueueNotification(notification{callee: c.callee, event: delivered, cancel: true})
	h.end(c)
	return nil
}
