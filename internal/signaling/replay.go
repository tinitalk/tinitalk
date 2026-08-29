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
			c.canReplay(entry.event, seq) {
			out = append(out, entry.event)
		}
	}
	return out
}

func (c *call) after(recipient string, seq uint64) []DeliveredEvent {
	var out []DeliveredEvent
	for _, entry := range c.replay {
		if entry.recipient == recipient && c.canReplay(entry.event, seq) {
			out = append(out, entry.event)
		}
	}
	return out
}

func (c *call) canReplay(event DeliveredEvent, seq uint64) bool {
	return event.Seq > seq && (c.state != callEnded || event.Type != "rtc.video")
}
