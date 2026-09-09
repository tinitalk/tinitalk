package protocol

import (
	"encoding/json"
	"testing"
)

func TestCallRejectReplyPayload(t *testing.T) {
	for _, tc := range []struct {
		payload string
		valid   bool
	}{
		{`{}`, true},
		{`{"reply_code":"cannot_talk"}`, true},
		{`{"reply_code":"call_me_later"}`, true},
		{`{"reply_code":"will_call_back"}`, true},
		{`{"reply_code":"future_code"}`, false},
		{`{"reply_code":""}`, false},
		{`{"reply_code":null}`, false},
		{`{"reply_code":2}`, false},
		{`{"reply_code":{}}`, false},
	} {
		t.Run(tc.payload, func(t *testing.T) {
			event := Event{ID: "018f7d51-3f90-7e63-b657-4a83a6a90101", CallID: "018f7d51-3f90-7e63-b657-4a83a6a90102", Type: "call.reject", Payload: json.RawMessage(tc.payload)}
			if err := event.Validate(); (err == nil) != tc.valid {
				t.Fatalf("Validate() = %v, valid=%v", err, tc.valid)
			}
		})
	}
}
