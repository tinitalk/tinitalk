package protocol

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
)

const (
	MaxEventBytes    = 16 * 1024
	RingTimeoutSecs  = 45
	EventBufferLimit = 256
)

type Event struct {
	ID      string          `json:"id"`
	CallID  string          `json:"call_id"`
	Type    string          `json:"type"`
	SentAt  int64           `json:"sent_at"`
	Payload json.RawMessage `json:"payload"`
}

var allowedTypes = map[string]struct{}{
	"call.start":          {},
	"call.incoming":       {},
	"call.ringing":        {},
	"call.accept":         {},
	"call.connected":      {},
	"call.reject":         {},
	"call.cancel":         {},
	"call.end":            {},
	"call.expire":         {},
	"call.resume":         {},
	"rtc.config":          {},
	"rtc.offer":           {},
	"rtc.answer":          {},
	"rtc.ice":             {},
	"rtc.restart":         {},
	"rtc.restart.request": {},
}

func Decode(raw []byte) (Event, error) {
	if len(raw) > MaxEventBytes {
		return Event{}, fmt.Errorf("event exceeds %d bytes", MaxEventBytes)
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()

	var event Event
	if err := decoder.Decode(&event); err != nil {
		return Event{}, err
	}
	if err := event.Validate(); err != nil {
		return Event{}, err
	}
	return event, nil
}

func (e Event) Encode() ([]byte, error) {
	if err := e.Validate(); err != nil {
		return nil, err
	}
	raw, err := json.Marshal(e)
	if err != nil {
		return nil, err
	}
	if len(raw) > MaxEventBytes {
		return nil, fmt.Errorf("event exceeds %d bytes", MaxEventBytes)
	}
	return raw, nil
}

func (e Event) Validate() error {
	if !looksLikeUUID(e.ID) {
		return errors.New("id must be a UUID")
	}
	if !looksLikeUUID(e.CallID) {
		return errors.New("call_id must be a UUID")
	}
	if _, ok := allowedTypes[e.Type]; !ok {
		return fmt.Errorf("unknown event type %q", e.Type)
	}
	if len(e.Payload) == 0 || !json.Valid(e.Payload) || e.Payload[0] != '{' {
		return errors.New("payload must be a JSON object")
	}
	if err := e.validatePayload(); err != nil {
		return err
	}
	return nil
}

func (e Event) validatePayload() error {
	switch e.Type {
	case "call.start":
		var payload struct {
			CalleeID string `json:"callee_id"`
		}
		if err := json.Unmarshal(e.Payload, &payload); err != nil {
			return err
		}
		if payload.CalleeID == "" {
			return errors.New("callee_id is required")
		}
	case "call.resume":
		var payload struct {
			LastSeq int64 `json:"last_seq"`
		}
		if err := json.Unmarshal(e.Payload, &payload); err != nil {
			return err
		}
		if payload.LastSeq < 0 {
			return errors.New("last_seq must be non-negative")
		}
	case "rtc.ice":
		var payload struct {
			Candidate string `json:"candidate"`
		}
		if err := json.Unmarshal(e.Payload, &payload); err != nil {
			return err
		}
		if payload.Candidate == "" {
			return errors.New("candidate is required")
		}
	}
	return nil
}

func looksLikeUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, c := range value {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
			continue
		}
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}
