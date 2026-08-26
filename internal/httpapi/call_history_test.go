package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"tinitalk/internal/state"
)

func TestCallHistoryEndpointReturnsNewestAuthenticatedPage(t *testing.T) {
	db, tokens := testDB(t)
	started := time.Date(2026, 8, 26, 10, 30, 0, 0, time.UTC)
	recordMissedHistoryCall(t, db, "call-1", started)
	server := NewServer(db, Options{AllowInsecureLoopback: true})

	response := request(t, server, http.MethodGet, "/api/calls?limit=1", nil, "bob", tokens["bob"])
	if response.Code != http.StatusOK {
		t.Fatalf("GET /api/calls status = %d, body %s", response.Code, response.Body.String())
	}
	var page struct {
		Items []struct {
			ID              int64  `json:"id"`
			PeerLogin       string `json:"peer_login"`
			PeerName        string `json:"peer_name"`
			Direction       string `json:"direction"`
			Outcome         string `json:"outcome"`
			StartedAt       int64  `json:"started_at"`
			DurationSeconds int64  `json:"duration_seconds"`
		} `json:"items"`
		NextBefore        int64 `json:"next_before"`
		LatestID          int64 `json:"latest_id"`
		UnreadMissedCount int   `json:"unread_missed_count"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 {
		t.Fatalf("history items = %d, want 1", len(page.Items))
	}
	item := page.Items[0]
	if item.PeerLogin != "alice" || item.PeerName != "Alice" || item.Direction != "incoming" || item.Outcome != "cancelled_after_ringing" {
		t.Fatalf("history item = %+v", item)
	}
	if item.ID == 0 || item.StartedAt != 1787740200 || item.DurationSeconds != 0 {
		t.Fatalf("history timing = %+v", item)
	}
	if page.LatestID != item.ID || page.NextBefore != 0 || page.UnreadMissedCount != 1 {
		t.Fatalf("history metadata = %+v", page)
	}
}

func TestCallHistoryReadEndpointClearsMissedCounter(t *testing.T) {
	db, tokens := testDB(t)
	recordMissedHistoryCall(t, db, "call-1", time.Date(2026, 8, 26, 10, 30, 0, 0, time.UTC))
	server := NewServer(db, Options{AllowInsecureLoopback: true})

	page, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	body := []byte(fmt.Sprintf(`{"through_id":%d}`, page.LatestID))
	response := request(t, server, http.MethodPut, "/api/calls/read", body, "bob", tokens["bob"])
	if response.Code != http.StatusNoContent {
		t.Fatalf("PUT /api/calls/read status = %d, body %s", response.Code, response.Body.String())
	}
	after, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if after.UnreadMissed != 0 {
		t.Fatalf("unread missed = %d, want 0", after.UnreadMissed)
	}
}

func recordMissedHistoryCall(t *testing.T, db *state.DB, callID string, started time.Time) {
	t.Helper()
	if err := db.StartCall(callID, "alice", "bob", started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallRinging(callID); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall(callID, state.CallOutcomeCancelledAfterRinging, started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
}
