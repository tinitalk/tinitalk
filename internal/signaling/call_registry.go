package signaling

// Registry helpers require h.mu. Outgoing invitations reserve the caller's
// active slot; incoming invitations do not own the callee's media slot.
func (h *Hub) addIncoming(c *call) {
	if h.incomingByUser[c.callee] == nil {
		h.incomingByUser[c.callee] = make(map[string]*call)
	}
	h.incomingByUser[c.callee][c.id] = c
}

func (h *Hub) removeIncoming(c *call) {
	delete(h.incomingByUser[c.callee], c.id)
	if len(h.incomingByUser[c.callee]) == 0 {
		delete(h.incomingByUser, c.callee)
	}
}

func (h *Hub) activateCall(c *call) {
	h.removeIncoming(c)
	h.activeByUser[c.caller] = c.id
	h.activeByUser[c.callee] = c.id
}

// Preserve the legacy single-call API: prefer the active/outgoing call,
// otherwise expose the oldest incoming invitation with a stable tie-breaker.
func (h *Hub) primaryCallID(user string) string {
	if id := h.activeByUser[user]; id != "" {
		return id
	}
	var first *call
	for _, c := range h.incomingByUser[user] {
		if first == nil || c.startedAt.Before(first.startedAt) ||
			(c.startedAt.Equal(first.startedAt) && c.id < first.id) {
			first = c
		}
	}
	if first != nil {
		return first.id
	}
	return ""
}

func (h *Hub) callsForUser(user string) []*call {
	calls := make([]*call, 0, len(h.incomingByUser[user])+1)
	if c := h.calls[h.activeByUser[user]]; c != nil && c.state != callEnded {
		calls = append(calls, c)
	}
	for _, c := range h.incomingByUser[user] {
		calls = append(calls, c)
	}
	return calls
}
