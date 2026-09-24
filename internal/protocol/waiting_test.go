package protocol

import (
	"encoding/json"
	"testing"
)

func TestWaitingPayload(t *testing.T) {
	for _, tc := range []struct {
		payload string
		valid   bool
	}{
		{`{"waiting":true}`, true}, {`{"waiting":false}`, true}, {`{}`, false},
		{`{"waiting":null}`, false}, {`{"waiting":"true"}`, false},
	} {
		e := Event{ID: "018f7d51-3f90-7e63-b657-4a83a6a90101", CallID: "018f7d51-3f90-7e63-b657-4a83a6a90102", Type: "call.waiting", Payload: json.RawMessage(tc.payload)}
		if err := e.Validate(); (err == nil) != tc.valid {
			t.Fatalf("%s: %v", tc.payload, err)
		}
	}
}
