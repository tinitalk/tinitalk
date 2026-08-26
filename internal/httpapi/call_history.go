package httpapi

import (
	"encoding/json"
	"net/http"
	"strconv"

	"tinitalk/internal/state"
)

type callHistoryItem struct {
	ID              int64  `json:"id"`
	PeerLogin       string `json:"peer_login"`
	PeerName        string `json:"peer_name"`
	Direction       string `json:"direction"`
	Outcome         string `json:"outcome"`
	StartedAt       int64  `json:"started_at"`
	DurationSeconds int64  `json:"duration_seconds"`
}

type callHistoryResponse struct {
	Items             []callHistoryItem `json:"items"`
	NextBefore        int64             `json:"next_before"`
	LatestID          int64             `json:"latest_id"`
	UnreadMissedCount int               `json:"unread_missed_count"`
}

func (s *Server) calls(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	limit, err := queryInt(r, "limit", 50)
	if err != nil || limit < 1 || limit > 100 {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	before, err := queryInt64(r, "before", 0)
	if err != nil || before < 0 {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	login := currentUser(r).Login
	peer := r.URL.Query().Get("peer")
	var page state.CallHistoryPage
	if peer == "" {
		page, err = s.db.CallHistory(login, before, limit)
	} else {
		page, err = s.db.CallHistoryForPeer(login, peer, before, limit)
	}
	if err != nil {
		if peer != "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		http.Error(w, "call history unavailable", http.StatusInternalServerError)
		return
	}
	response := callHistoryResponse{
		Items:             make([]callHistoryItem, 0, len(page.Items)),
		NextBefore:        page.NextBefore,
		LatestID:          page.LatestID,
		UnreadMissedCount: page.UnreadMissed,
	}
	for _, item := range page.Items {
		response.Items = append(response.Items, callHistoryItem{
			ID:              item.ID,
			PeerLogin:       item.PeerLogin,
			PeerName:        item.PeerName,
			Direction:       string(item.Direction),
			Outcome:         callOutcomeName(item.Outcome),
			StartedAt:       item.StartedAt.Unix(),
			DurationSeconds: item.DurationSeconds,
		})
	}
	writeJSON(w, response)
}

func (s *Server) readCalls(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var request struct {
		ThroughID *int64 `json:"through_id"`
		PeerLogin string `json:"peer_login"`
	}
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil || request.ThroughID == nil || *request.ThroughID < 0 {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	login := currentUser(r).Login
	var unread int
	var err error
	if request.PeerLogin == "" {
		unread, err = s.db.MarkCallHistoryReadAndCount(login, *request.ThroughID)
	} else {
		unread, err = s.db.MarkCallHistoryReadForPeer(login, request.PeerLogin, *request.ThroughID)
	}
	if err != nil {
		if request.PeerLogin != "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		http.Error(w, "call history unavailable", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]int{"unread_missed_count": unread})
}

func queryInt(r *http.Request, name string, fallback int) (int, error) {
	value := r.URL.Query().Get(name)
	if value == "" {
		return fallback, nil
	}
	return strconv.Atoi(value)
}

func queryInt64(r *http.Request, name string, fallback int64) (int64, error) {
	value := r.URL.Query().Get(name)
	if value == "" {
		return fallback, nil
	}
	return strconv.ParseInt(value, 10, 64)
}

func callOutcomeName(outcome state.CallOutcome) string {
	switch outcome {
	case state.CallOutcomeUnreachable:
		return "unreachable"
	case state.CallOutcomeUnanswered:
		return "unanswered"
	case state.CallOutcomeBusy:
		return "busy"
	case state.CallOutcomeRejected:
		return "rejected"
	case state.CallOutcomeCancelledBeforeRinging:
		return "cancelled_before_ringing"
	case state.CallOutcomeCancelledAfterRinging:
		return "cancelled_after_ringing"
	case state.CallOutcomeConnectionFailed:
		return "connection_failed"
	case state.CallOutcomeCompleted:
		return "completed"
	case state.CallOutcomeInterruptedBeforeAnswer:
		return "interrupted_before_answer"
	case state.CallOutcomeInterrupted:
		return "interrupted"
	default:
		return "unknown"
	}
}
