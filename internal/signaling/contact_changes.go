package signaling

import (
	"encoding/json"
	"tinitalk/internal/protocol"
)

// EnableContactChanges opts a browser into account events outside call replay.
func (h *Hub) EnableContactChanges(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if client != nil && !client.closed {
		client.contactChanges = true
	}
}

func (h *Hub) ContactChanged(user, contact, sessionID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	payload, _ := json.Marshal(map[string]string{"contact_login": contact})
	event := DeliveredEvent{Event: protocol.Event{Type: "contact.changed", Payload: payload}}
	for client := range h.clients[user] {
		if client.contactChanges && client.sessionID == sessionID {
			h.deliverClient(client, event)
		}
	}
}
