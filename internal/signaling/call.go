package signaling

import "time"

type callState uint8

const (
	callRinging callState = iota
	callActive
	callEnded
)

type replayEntry struct {
	recipient string
	event     DeliveredEvent
}

type call struct {
	id                string
	caller            string
	callee            string
	nextSeq           uint64
	seen              map[string]struct{}
	seenOrder         []string
	replay            []replayEntry
	aliases           []string
	startedAt         time.Time
	endedAt           time.Time
	iceWindowAt       time.Time
	iceCount          int
	lastRestart       time.Time
	offlineSince      map[string]time.Time
	state             callState
	supportsCrossCall bool
}

func (c *call) remember(eventID string) {
	c.seen[eventID] = struct{}{}
	c.seenOrder = append(c.seenOrder, eventID)
	if len(c.seenOrder) > ReplayLimit {
		delete(c.seen, c.seenOrder[0])
		c.seenOrder = c.seenOrder[1:]
	}
}

func (c *call) participant(user string) bool {
	return user == c.caller || user == c.callee
}

func (c *call) other(user string) string {
	if user == c.caller {
		return c.callee
	}
	return c.caller
}
