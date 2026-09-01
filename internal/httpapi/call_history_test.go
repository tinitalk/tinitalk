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
	if err := db.SetContactName("bob", "alice", "Mom"); err != nil {
		t.Fatal(err)
	}
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
			Reached         bool   `json:"reached"`
			StartedAt       int64  `json:"started_at"`
			DurationSeconds int64  `json:"duration_seconds"`
		} `json:"items"`
		NextBefore        int64 `json:"next_before"`
		LatestID          int64 `json:"latest_id"`
		UnreadMissedCount int   `json:"unread_missed_count"`
		UnreadMissed      []struct {
			PeerLogin string `json:"peer_login"`
			PeerName  string `json:"peer_name"`
			StartedAt int64  `json:"started_at"`
		} `json:"unread_missed"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 {
		t.Fatalf("history items = %d, want 1", len(page.Items))
	}
	item := page.Items[0]
	if item.PeerLogin != "alice" || item.PeerName != "Mom" || item.Direction != "incoming" || item.Outcome != "cancelled_after_ringing" {
		t.Fatalf("history item = %+v", item)
	}
	if !item.Reached {
		t.Fatalf("history reached = false, want true")
	}
	if item.ID == 0 || item.StartedAt != 1787740200 || item.DurationSeconds != 0 {
		t.Fatalf("history timing = %+v", item)
	}
	if page.LatestID != item.ID || page.NextBefore != 0 || page.UnreadMissedCount != 1 {
		t.Fatalf("history metadata = %+v", page)
	}
	if len(page.UnreadMissed) != 1 || page.UnreadMissed[0].PeerLogin != "alice" || page.UnreadMissed[0].PeerName != "Mom" || page.UnreadMissed[0].StartedAt != started.Unix() {
		t.Fatalf("unread missed contacts = %+v", page.UnreadMissed)
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
	if response.Code != http.StatusOK {
		t.Fatalf("PUT /api/calls/read status = %d, body %s", response.Code, response.Body.String())
	}
	var result struct {
		UnreadMissedCount int `json:"unread_missed_count"`
		UnreadMissed      []struct {
			PeerLogin string `json:"peer_login"`
			StartedAt int64  `json:"started_at"`
		} `json:"unread_missed"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.UnreadMissedCount != 0 {
		t.Fatalf("read response = %+v, want zero unread", result)
	}
	after, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if after.UnreadMissed != 0 {
		t.Fatalf("unread missed = %d, want 0", after.UnreadMissed)
	}
}

func TestCallHistoryEndpointFiltersAndMarksOneContactRead(t *testing.T) {
	db, tokens := testDB(t)
	if _, err := db.AddUser("carol", "Carol"); err != nil {
		t.Fatal(err)
	}
	started := time.Date(2026, 8, 26, 10, 30, 0, 0, time.UTC)
	recordMissedHistoryCallFrom(t, db, "alice-call", "alice", "bob", started)
	recordMissedHistoryCallFrom(t, db, "carol-call", "carol", "bob", started.Add(time.Hour))
	server := NewServer(db, Options{AllowInsecureLoopback: true})

	response := request(t, server, http.MethodGet, "/api/calls?peer=alice&limit=10", nil, "bob", tokens["bob"])
	if response.Code != http.StatusOK {
		t.Fatalf("filtered history status = %d, body %s", response.Code, response.Body.String())
	}
	var page callHistoryResponse
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].PeerLogin != "alice" || page.UnreadMissedCount != 2 {
		t.Fatalf("filtered history = %+v", page)
	}

	body := []byte(fmt.Sprintf(`{"through_id":%d,"peer_login":"alice"}`, page.LatestID))
	read := request(t, server, http.MethodPut, "/api/calls/read", body, "bob", tokens["bob"])
	if read.Code != http.StatusOK {
		t.Fatalf("filtered read status = %d, body %s", read.Code, read.Body.String())
	}
	var result struct {
		UnreadMissedCount int `json:"unread_missed_count"`
		UnreadMissed      []struct {
			PeerLogin string `json:"peer_login"`
			StartedAt int64  `json:"started_at"`
		} `json:"unread_missed"`
	}
	if err := json.Unmarshal(read.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.UnreadMissedCount != 1 {
		t.Fatalf("unread after reading alice = %d, want 1", result.UnreadMissedCount)
	}
	if len(result.UnreadMissed) != 1 || result.UnreadMissed[0].PeerLogin != "carol" || result.UnreadMissed[0].StartedAt != started.Add(time.Hour).Unix() {
		t.Fatalf("unread contacts after reading alice = %+v", result.UnreadMissed)
	}

	unknown := request(t, server, http.MethodGet, "/api/calls?peer=unknown", nil, "bob", tokens["bob"])
	if unknown.Code != http.StatusBadRequest {
		t.Fatalf("unknown peer status = %d, want 400", unknown.Code)
	}
}

func recordMissedHistoryCall(t *testing.T, db *state.DB, callID string, started time.Time) {
	recordMissedHistoryCallFrom(t, db, callID, "alice", "bob", started)
}

func recordMissedHistoryCallFrom(t *testing.T, db *state.DB, callID, caller, callee string, started time.Time) {
	t.Helper()
	if err := db.StartCall(callID, caller, callee, started); err != nil {
		t.Fatal(err)
	}
	if err := db.MarkCallRinging(callID); err != nil {
		t.Fatal(err)
	}
	if err := db.FinishCall(callID, state.CallOutcomeCancelledAfterRinging, started.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
}
