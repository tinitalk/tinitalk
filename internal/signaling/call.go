package signaling

type call struct {
	id           string
	caller       string
	callee       string
	nextSeq      uint64
	seen         map[string]struct{}
	replay       []DeliveredEvent
	waitingEnded bool
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
