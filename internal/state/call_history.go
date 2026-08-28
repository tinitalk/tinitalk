package state

import (
	"database/sql"
	"errors"
	"fmt"
	"time"
)

type CallOutcome uint8

const (
	CallOutcomePending CallOutcome = iota
	CallOutcomeUnreachable
	CallOutcomeUnanswered
	CallOutcomeBusy
	CallOutcomeRejected
	CallOutcomeCancelledBeforeRinging
	CallOutcomeCancelledAfterRinging
	CallOutcomeConnectionFailed
	CallOutcomeCompleted
	CallOutcomeInterruptedBeforeAnswer
	CallOutcomeInterrupted
)

type CallDirection string

const (
	CallDirectionIncoming CallDirection = "incoming"
	CallDirectionOutgoing CallDirection = "outgoing"
)

type CallHistoryItem struct {
	ID              int64
	CallID          string
	PeerLogin       string
	PeerName        string
	Direction       CallDirection
	Outcome         CallOutcome
	Reached         bool
	StartedAt       time.Time
	DurationSeconds int64
}

type UnreadMissedContact struct {
	PeerLogin string
	StartedAt time.Time
}

type CallUnreadState struct {
	Count                 int
	LatestUnreadByContact []UnreadMissedContact
}

type CallHistoryPage struct {
	Items              []CallHistoryItem
	NextBefore         int64
	LatestID           int64
	UnreadMissed       int
	LatestUnreadMissed []UnreadMissedContact
}

type callHistoryQueryer interface {
	Query(query string, args ...any) (*sql.Rows, error)
	QueryRow(query string, args ...any) *sql.Row
}

type callStage uint8

const (
	callStageStarted callStage = iota
	callStageRinging
	callStageAccepted
	callStageConnected
)

func (db *DB) StartCall(callID, caller, callee string, startedAt time.Time) error {
	result, err := db.sql.Exec(`
		INSERT INTO call_history(call_id, caller_id, callee_id, stage, started_at)
		SELECT ?, caller.id, callee.id, ?, ?
		FROM users caller, users callee
		WHERE caller.login = ? AND caller.disabled = 0
			AND callee.login = ? AND callee.disabled = 0
	`, callID, callStageStarted, startedAt.Unix(), caller, callee)
	if err != nil {
		return err
	}
	return requireAffected(result, "call participant not found")
}

func (db *DB) RecordBusyCall(callID, caller, callee string, startedAt time.Time) error {
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	result, err := tx.Exec(`
		INSERT OR IGNORE INTO call_history(
			call_id, caller_id, callee_id, stage, outcome, started_at, ended_at
		)
		SELECT ?, caller.id, callee.id, ?, ?, ?, ?
		FROM users caller, users callee
		WHERE caller.login = ? AND caller.disabled = 0
			AND callee.login = ? AND callee.disabled = 0
	`, callID, callStageStarted, CallOutcomeBusy, startedAt.Unix(), startedAt.Unix(), caller, callee)
	if err != nil {
		return err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if affected > 0 {
		if _, err := tx.Exec(`
			INSERT INTO call_history_unread(call_history_id, user_id)
			SELECT id, callee_id FROM call_history WHERE call_id = ?
		`, callID); err != nil {
			return err
		}
		return tx.Commit()
	}
	var storedCaller, storedCallee string
	var outcome CallOutcome
	err = tx.QueryRow(`
		SELECT caller.login, callee.login, h.outcome
		FROM call_history h
		JOIN users caller ON caller.id = h.caller_id
		JOIN users callee ON callee.id = h.callee_id
		WHERE h.call_id = ?
	`, callID).Scan(&storedCaller, &storedCallee, &outcome)
	if errors.Is(err, sql.ErrNoRows) {
		return errors.New("call participant not found")
	}
	if err != nil {
		return err
	}
	if storedCaller != caller || storedCallee != callee || outcome != CallOutcomeBusy {
		return errors.New("call ID is already in use")
	}
	return tx.Commit()
}

func (db *DB) MarkCallRinging(callID string) error {
	return db.markCallStage(callID, callStageRinging)
}

func (db *DB) MarkCallAccepted(callID string) error {
	return db.markCallStage(callID, callStageAccepted)
}

func (db *DB) MarkCallConnected(callID string, connectedAt time.Time) error {
	result, err := db.sql.Exec(`
		UPDATE call_history
		SET stage = ?, connected_at = COALESCE(connected_at, ?)
		WHERE call_id = ? AND ended_at IS NULL
	`, callStageConnected, connectedAt.Unix(), callID)
	if err != nil {
		return err
	}
	return requireAffected(result, "active call not found")
}

func (db *DB) markCallStage(callID string, stage callStage) error {
	result, err := db.sql.Exec(`
		UPDATE call_history
		SET stage = CASE WHEN stage < ? THEN ? ELSE stage END
		WHERE call_id = ? AND ended_at IS NULL
	`, stage, stage, callID)
	if err != nil {
		return err
	}
	return requireAffected(result, "active call not found")
}

func (db *DB) FinishCall(callID string, outcome CallOutcome, endedAt time.Time) error {
	if outcome == CallOutcomePending {
		return errors.New("terminal call outcome is required")
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	result, err := tx.Exec(`
		UPDATE call_history SET outcome = ?, ended_at = ?
		WHERE call_id = ? AND ended_at IS NULL
	`, outcome, endedAt.Unix(), callID)
	if err != nil {
		return err
	}
	if err := requireAffected(result, "active call not found"); err != nil {
		return err
	}
	if isUnreadMissedOutcome(outcome) {
		if _, err := tx.Exec(`
			INSERT INTO call_history_unread(call_history_id, user_id)
			SELECT id, callee_id FROM call_history WHERE call_id = ?
		`, callID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (db *DB) RecoverCallHistory(endedAt time.Time) error {
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`
		INSERT OR IGNORE INTO call_history_unread(call_history_id, user_id)
		SELECT history.id, history.callee_id
		FROM call_history history
		WHERE history.ended_at IS NULL
			AND history.stage < ?
	`, callStageAccepted); err != nil {
		return err
	}
	if _, err := tx.Exec(`
		UPDATE call_history
		SET outcome = CASE stage
			WHEN ? THEN ?
			WHEN ? THEN ?
			ELSE ?
		END, ended_at = ?
		WHERE ended_at IS NULL
	`,
		callStageConnected, CallOutcomeInterrupted,
		callStageAccepted, CallOutcomeConnectionFailed,
		CallOutcomeInterruptedBeforeAnswer,
		endedAt.Unix(),
	); err != nil {
		return err
	}
	return tx.Commit()
}

func isUnreadMissedOutcome(outcome CallOutcome) bool {
	switch outcome {
	case CallOutcomeUnreachable,
		CallOutcomeUnanswered,
		CallOutcomeBusy,
		CallOutcomeCancelledBeforeRinging,
		CallOutcomeCancelledAfterRinging,
		CallOutcomeInterruptedBeforeAnswer:
		return true
	default:
		return false
	}
}

func (db *DB) CallHistory(login string, before int64, limit int) (CallHistoryPage, error) {
	return db.callHistory(login, "", before, limit)
}

func (db *DB) CallHistoryForPeer(login, peer string, before int64, limit int) (CallHistoryPage, error) {
	if peer == "" {
		return CallHistoryPage{}, errors.New("history peer is required")
	}
	return db.callHistory(login, peer, before, limit)
}

func (db *DB) callHistory(login, peer string, before int64, limit int) (CallHistoryPage, error) {
	var page CallHistoryPage
	if limit < 1 || limit > 100 {
		return page, errors.New("history limit must be between 1 and 100")
	}
	userID, err := db.userID(login)
	if err != nil {
		return page, err
	}
	var peerID int64
	if peer != "" {
		peerID, err = db.userID(peer)
		if err != nil {
			return page, err
		}
		if peerID == userID {
			return page, errors.New("history peer must be another user")
		}
	}
	if err := db.sql.QueryRow(`
		SELECT COALESCE(MAX(id), 0) FROM call_history
		WHERE ended_at IS NOT NULL
			AND (caller_id = ? OR callee_id = ?)
			AND (? = 0 OR (caller_id = ? AND callee_id = ?) OR (caller_id = ? AND callee_id = ?))
	`, userID, userID, peerID, userID, peerID, peerID, userID).Scan(&page.LatestID); err != nil {
		return page, err
	}
	unread, err := unreadMissedState(db.sql, userID)
	if err != nil {
		return page, err
	}
	page.UnreadMissed = unread.Count
	page.LatestUnreadMissed = unread.LatestUnreadByContact

	rows, err := db.sql.Query(`
		SELECT h.id, h.call_id, h.caller_id, peer.login,
			COALESCE(personal.custom_name, peer.display_name),
			h.outcome, h.stage, h.started_at, h.connected_at, h.ended_at
		FROM call_history h
		JOIN users peer ON peer.id = CASE WHEN h.caller_id = ? THEN h.callee_id ELSE h.caller_id END
		LEFT JOIN user_contacts personal
			ON personal.owner_user_id = ? AND personal.contact_user_id = peer.id
		WHERE h.ended_at IS NOT NULL
			AND (h.caller_id = ? OR h.callee_id = ?)
			AND (? = 0 OR (h.caller_id = ? AND h.callee_id = ?) OR (h.caller_id = ? AND h.callee_id = ?))
			AND (? = 0 OR h.id < ?)
		ORDER BY h.id DESC
		LIMIT ?
	`, userID, userID, userID, userID,
		peerID, userID, peerID, peerID, userID,
		before, before, limit+1)
	if err != nil {
		return page, err
	}
	defer rows.Close()
	for rows.Next() {
		var item CallHistoryItem
		var callerID, startedAt, endedAt int64
		var stage callStage
		var connectedAt sql.NullInt64
		if err := rows.Scan(
			&item.ID,
			&item.CallID,
			&callerID,
			&item.PeerLogin,
			&item.PeerName,
			&item.Outcome,
			&stage,
			&startedAt,
			&connectedAt,
			&endedAt,
		); err != nil {
			return page, err
		}
		if callerID == userID {
			item.Direction = CallDirectionOutgoing
		} else {
			item.Direction = CallDirectionIncoming
		}
		item.Reached = stage >= callStageRinging ||
			item.Outcome == CallOutcomeBusy || item.Outcome == CallOutcomeRejected
		item.StartedAt = time.Unix(startedAt, 0).UTC()
		if connectedAt.Valid && endedAt > connectedAt.Int64 {
			item.DurationSeconds = endedAt - connectedAt.Int64
		}
		page.Items = append(page.Items, item)
	}
	if err := rows.Err(); err != nil {
		return page, err
	}
	if len(page.Items) > limit {
		page.Items = page.Items[:limit]
		page.NextBefore = page.Items[len(page.Items)-1].ID
	}
	return page, nil
}

func (db *DB) MarkCallHistoryRead(login string, throughID int64) error {
	_, err := db.markCallHistoryRead(login, "", throughID)
	return err
}

func (db *DB) MarkCallHistoryReadAndCount(login string, throughID int64) (int, error) {
	state, err := db.markCallHistoryRead(login, "", throughID)
	return state.Count, err
}

func (db *DB) MarkCallHistoryReadAndState(login string, throughID int64) (CallUnreadState, error) {
	return db.markCallHistoryRead(login, "", throughID)
}

func (db *DB) MarkCallHistoryReadForPeer(login, peer string, throughID int64) (int, error) {
	if peer == "" {
		return 0, errors.New("history peer is required")
	}
	state, err := db.markCallHistoryRead(login, peer, throughID)
	return state.Count, err
}

func (db *DB) MarkCallHistoryReadForPeerAndState(login, peer string, throughID int64) (CallUnreadState, error) {
	if peer == "" {
		return CallUnreadState{}, errors.New("history peer is required")
	}
	return db.markCallHistoryRead(login, peer, throughID)
}

func (db *DB) markCallHistoryRead(login, peer string, throughID int64) (CallUnreadState, error) {
	if throughID < 0 {
		return CallUnreadState{}, errors.New("history marker must be non-negative")
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return CallUnreadState{}, err
	}
	defer tx.Rollback()
	var userID, peerID, latestID int64
	if err := tx.QueryRow("SELECT id FROM users WHERE login = ? AND disabled = 0", login).Scan(&userID); err != nil {
		return CallUnreadState{}, err
	}
	if peer != "" {
		if err := tx.QueryRow("SELECT id FROM users WHERE login = ? AND disabled = 0", peer).Scan(&peerID); err != nil {
			return CallUnreadState{}, err
		}
		if peerID == userID {
			return CallUnreadState{}, errors.New("history peer must be another user")
		}
	}
	if err := tx.QueryRow(`
		SELECT COALESCE(MAX(id), 0) FROM call_history
		WHERE ended_at IS NOT NULL
			AND (caller_id = ? OR callee_id = ?)
			AND (? = 0 OR (caller_id = ? AND callee_id = ?) OR (caller_id = ? AND callee_id = ?))
	`, userID, userID, peerID, userID, peerID, peerID, userID).Scan(&latestID); err != nil {
		return CallUnreadState{}, err
	}
	if throughID > latestID {
		throughID = latestID
	}
	if peerID == 0 {
		if _, err := tx.Exec(`
			INSERT INTO call_history_reads(user_id, through_id) VALUES(?, ?)
			ON CONFLICT(user_id) DO UPDATE SET
				through_id = MAX(call_history_reads.through_id, excluded.through_id)
		`, userID, throughID); err != nil {
			return CallUnreadState{}, err
		}
	}
	if _, err := tx.Exec(`
		DELETE FROM call_history_unread
		WHERE user_id = ? AND call_history_id <= ?
			AND (? = 0 OR call_history_id IN (
				SELECT id FROM call_history WHERE caller_id = ? AND callee_id = ?
			))
	`, userID, throughID, peerID, peerID, userID); err != nil {
		return CallUnreadState{}, err
	}
	unread, err := unreadMissedState(tx, userID)
	if err != nil {
		return CallUnreadState{}, err
	}
	if err := tx.Commit(); err != nil {
		return CallUnreadState{}, err
	}
	return unread, nil
}

func unreadMissedState(queryer callHistoryQueryer, userID int64) (CallUnreadState, error) {
	var state CallUnreadState
	if err := queryer.QueryRow(
		"SELECT COUNT(*) FROM call_history_unread WHERE user_id = ?",
		userID,
	).Scan(&state.Count); err != nil {
		return state, err
	}
	rows, err := queryer.Query(`
		SELECT caller.login, MAX(history.started_at)
		FROM call_history_unread unread
		JOIN call_history history ON history.id = unread.call_history_id
		JOIN users caller ON caller.id = history.caller_id
		WHERE unread.user_id = ?
		GROUP BY caller.id, caller.login
		ORDER BY MAX(history.id) DESC
	`, userID)
	if err != nil {
		return state, err
	}
	defer rows.Close()
	for rows.Next() {
		var contact UnreadMissedContact
		var startedAt int64
		if err := rows.Scan(&contact.PeerLogin, &startedAt); err != nil {
			return state, err
		}
		contact.StartedAt = time.Unix(startedAt, 0).UTC()
		state.LatestUnreadByContact = append(state.LatestUnreadByContact, contact)
	}
	return state, rows.Err()
}

func (db *DB) userID(login string) (int64, error) {
	var id int64
	err := db.sql.QueryRow("SELECT id FROM users WHERE login = ? AND disabled = 0", login).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, fmt.Errorf("user %q not found", login)
	}
	return id, err
}
