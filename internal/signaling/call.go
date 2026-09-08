package signaling

import "time"

type callState uint8

type sasPhase uint8

const (
	callRinging callState = iota
	callActive
	callEnded
)

const (
	sasWaitingCommit sasPhase = iota
	sasWaitingKey
	sasWaitingReveal
	sasComplete
)

type replayEntry struct {
	recipient string
	deviceID  string
	event     DeliveredEvent
}

type call struct {
	id                   string
	caller               string
	callee               string
	callerDeviceID       string
	calleeDeviceID       string
	nextSeq              uint64
	seen                 map[string]struct{}
	seenOrder            []string
	replay               []replayEntry
	aliases              []string
	startedAt            time.Time
	ringingAt            time.Time
	connectedAt          time.Time
	endedAt              time.Time
	iceWindowAt          time.Time
	iceCount             int
	lastRestart          time.Time
	lastRestartRequest   time.Time
	offlineSince         map[string]time.Time
	state                callState
	supportsCrossCall    bool
	callerSupportsVideo  bool
	calleeSupportsVideo  bool
	callerSupportsScreen bool
	calleeSupportsScreen bool
	callerSupportsSAS    bool
	calleeSupportsSAS    bool
	sasPhase             sasPhase
	sasStartedAt         time.Time
	screenPresenter      string
	screenShareID        string
	screenCallerReady    bool
	screenCalleeReady    bool
	screenPreparingAt    time.Time
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

func (c *call) deviceID(user string) string {
	if user == c.caller {
		return c.callerDeviceID
	}
	return c.calleeDeviceID
}

func (c *call) devicesBound() bool {
	return c.callerDeviceID != "" && c.calleeDeviceID != ""
}

func (c *call) videoAllowed() bool {
	return c.devicesBound() && c.callerSupportsVideo && c.calleeSupportsVideo
}

func (c *call) screenAllowed() bool {
	return c.videoAllowed() && c.callerSupportsScreen && c.calleeSupportsScreen
}

func (c *call) sasAllowed() bool {
	return c.devicesBound() && c.callerSupportsSAS && c.calleeSupportsSAS
}

func (c *call) acceptSASEvent(sender, eventType string, now time.Time) error {
	if !c.sasAllowed() {
		return clientError{message: "security code is not allowed for this call", code: "call_sas_unavailable"}
	}
	if !c.sasStartedAt.IsZero() && now.Sub(c.sasStartedAt) > SASExchangeTimeout {
		return clientError{message: "security code exchange timed out", code: "call_sas_timeout"}
	}
	switch eventType {
	case "rtc.sas.commit":
		if sender != c.caller || c.sasPhase != sasWaitingCommit {
			return clientError{message: "unexpected security code commitment", code: "call_sas_invalid"}
		}
		c.sasPhase = sasWaitingKey
		c.sasStartedAt = now
	case "rtc.sas.key":
		if sender != c.callee || c.sasPhase != sasWaitingKey {
			return clientError{message: "unexpected security code key", code: "call_sas_invalid"}
		}
		c.sasPhase = sasWaitingReveal

	case "rtc.sas.reveal":
		if sender != c.caller || c.sasPhase != sasWaitingReveal {
			return clientError{message: "unexpected security code reveal", code: "call_sas_invalid"}
		}
		c.sasPhase = sasComplete
	default:
		return clientError{message: "unknown security code event", code: "call_sas_invalid"}
	}
	return nil
}

func (c *call) screenReady() bool {
	return c.screenPresenter != "" && c.screenCallerReady && c.screenCalleeReady
}

func (c *call) clearScreen() {
	c.screenPresenter, c.screenShareID = "", ""
	c.screenCallerReady, c.screenCalleeReady = false, false
	c.screenPreparingAt = time.Time{}
}
