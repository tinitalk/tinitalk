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
	StartedAt       time.Time
	DurationSeconds int64
}

type CallHistoryPage struct {
	Items        []CallHistoryItem
	NextBefore   int64
	LatestID     int64
	UnreadMissed int
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
	result, err := db.sql.Exec(`
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
	if err != nil || affected > 0 {
		return err
	}
	var storedCaller, storedCallee string
	var outcome CallOutcome
	err = db.sql.QueryRow(`
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
	return nil
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
	result, err := db.sql.Exec(`
		UPDATE call_history SET outcome = ?, ended_at = ?
		WHERE call_id = ? AND ended_at IS NULL
	`, outcome, endedAt.Unix(), callID)
	if err != nil {
		return err
	}
	return requireAffected(result, "active call not found")
}

func (db *DB) RecoverCallHistory(endedAt time.Time) error {
	_, err := db.sql.Exec(`
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
	)
	return err
}

func (db *DB) CallHistory(login string, before int64, limit int) (CallHistoryPage, error) {
	var page CallHistoryPage
	if limit < 1 || limit > 100 {
		return page, errors.New("history limit must be between 1 and 100")
	}
	userID, err := db.userID(login)
	if err != nil {
		return page, err
	}
	if err := db.sql.QueryRow(`
		SELECT COALESCE(MAX(id), 0) FROM call_history
		WHERE ended_at IS NOT NULL AND (caller_id = ? OR callee_id = ?)
	`, userID, userID).Scan(&page.LatestID); err != nil {
		return page, err
	}
	if err := db.sql.QueryRow(`
		SELECT COUNT(*) FROM call_history
		WHERE callee_id = ? AND ended_at IS NOT NULL
			AND id > COALESCE((SELECT through_id FROM call_history_reads WHERE user_id = ?), 0)
			AND outcome IN (?, ?)
	`, userID, userID, CallOutcomeUnanswered, CallOutcomeCancelledAfterRinging).Scan(&page.UnreadMissed); err != nil {
		return page, err
	}

	rows, err := db.sql.Query(`
		SELECT h.id, h.call_id, h.caller_id, peer.login, peer.display_name,
			h.outcome, h.started_at, h.connected_at, h.ended_at
		FROM call_history h
		JOIN users peer ON peer.id = CASE WHEN h.caller_id = ? THEN h.callee_id ELSE h.caller_id END
		WHERE h.ended_at IS NOT NULL
			AND (h.caller_id = ? OR h.callee_id = ?)
			AND (? = 0 OR h.id < ?)
		ORDER BY h.id DESC
		LIMIT ?
	`, userID, userID, userID, before, before, limit+1)
	if err != nil {
		return page, err
	}
	defer rows.Close()
	for rows.Next() {
		var item CallHistoryItem
		var callerID, startedAt, endedAt int64
		var connectedAt sql.NullInt64
		if err := rows.Scan(
			&item.ID,
			&item.CallID,
			&callerID,
			&item.PeerLogin,
			&item.PeerName,
			&item.Outcome,
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
	if throughID < 0 {
		return errors.New("history marker must be non-negative")
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var userID, latestID int64
	if err := tx.QueryRow("SELECT id FROM users WHERE login = ? AND disabled = 0", login).Scan(&userID); err != nil {
		return err
	}
	if err := tx.QueryRow(`
		SELECT COALESCE(MAX(id), 0) FROM call_history
		WHERE ended_at IS NOT NULL AND (caller_id = ? OR callee_id = ?)
	`, userID, userID).Scan(&latestID); err != nil {
		return err
	}
	if throughID > latestID {
		throughID = latestID
	}
	if _, err := tx.Exec(`
		INSERT INTO call_history_reads(user_id, through_id) VALUES(?, ?)
		ON CONFLICT(user_id) DO UPDATE SET
			through_id = MAX(call_history_reads.through_id, excluded.through_id)
	`, userID, throughID); err != nil {
		return err
	}
	return tx.Commit()
}

func (db *DB) userID(login string) (int64, error) {
	var id int64
	err := db.sql.QueryRow("SELECT id FROM users WHERE login = ? AND disabled = 0", login).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, fmt.Errorf("user %q not found", login)
	}
	return id, err
}
