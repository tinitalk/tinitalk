package signaling

func (c *call) appendReplay(event DeliveredEvent) {
	c.replay = append(c.replay, event)
	if len(c.replay) > ReplayLimit {
		copy(c.replay, c.replay[len(c.replay)-ReplayLimit:])
		c.replay = c.replay[:ReplayLimit]
	}
}

func (c *call) after(seq uint64) []DeliveredEvent {
	var out []DeliveredEvent
	for _, event := range c.replay {
		if event.Seq > seq {
			out = append(out, event)
		}
	}
	return out
}
