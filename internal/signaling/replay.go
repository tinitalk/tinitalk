package signaling

func (c *call) appendReplay(recipient string, event DeliveredEvent) {
	c.appendDeviceReplay(recipient, "", event)
}

func (c *call) appendDeviceReplay(recipient, deviceID string, event DeliveredEvent) {
	c.replay = append(c.replay, replayEntry{recipient: recipient, deviceID: deviceID, event: event})
	if len(c.replay) > ReplayLimit {
		copy(c.replay, c.replay[len(c.replay)-ReplayLimit:])
		c.replay = c.replay[:ReplayLimit]
	}
}

func (c *call) afterDevice(recipient, deviceID string, seq uint64) []DeliveredEvent {
	var out []DeliveredEvent
	for _, entry := range c.replay {
		if entry.recipient == recipient &&
			(entry.deviceID == "" || entry.deviceID == deviceID) &&
			entry.event.Seq > seq {
			out = append(out, entry.event)
		}
	}
	return out
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
