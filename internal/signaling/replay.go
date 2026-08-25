package signaling

func (c *call) appendReplay(recipient string, event DeliveredEvent) {
	c.replay = append(c.replay, replayEntry{recipient: recipient, event: event})
	if len(c.replay) > ReplayLimit {
		copy(c.replay, c.replay[len(c.replay)-ReplayLimit:])
		c.replay = c.replay[:ReplayLimit]
	}
}

func (c *call) after(recipient string, seq uint64) []DeliveredEvent {
	var out []DeliveredEvent
	for _, entry := range c.replay {
		if entry.recipient == recipient && entry.event.Seq > seq {
			out = append(out, entry.event)
		}
	}
	return out
}
