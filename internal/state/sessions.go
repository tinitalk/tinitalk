package state

import (
	"database/sql"
	"errors"
)

var ErrSessionReplaced = errors.New("session replaced")

type AccountSession struct {
	DeviceID  string
	SessionID string
	UpdatedAt int64
}

type SessionClaim struct {
	Current        AccountSession
	Previous       *AccountSession
	RevokedDevices []Device
	Changed        bool
}

func (db *DB) CurrentSession(login string) (AccountSession, bool, error) {
	var session AccountSession
	err := db.sql.QueryRow(`
		SELECT session.device_id, session.session_id, session.updated_at
		FROM account_sessions session
		JOIN users user ON user.id = session.user_id
		WHERE user.login = ?
	`, login).Scan(&session.DeviceID, &session.SessionID, &session.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return AccountSession{}, false, nil
	}
	if err != nil {
		return AccountSession{}, false, err
	}
	return session, true, nil
}

func (db *DB) ClaimSession(login, deviceID string) (SessionClaim, error) {
	if deviceID == "" {
		return SessionClaim{}, errors.New("device ID is required")
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return SessionClaim{}, err
	}
	defer tx.Rollback()

	var userID int64
	if err := tx.QueryRow("SELECT id FROM users WHERE login = ?", login).Scan(&userID); err != nil {
		return SessionClaim{}, err
	}
	claim := SessionClaim{}
	var previous AccountSession
	err = tx.QueryRow(`
		SELECT device_id, session_id, updated_at
		FROM account_sessions
		WHERE user_id = ?
	`, userID).Scan(&previous.DeviceID, &previous.SessionID, &previous.UpdatedAt)
	if err == nil && previous.DeviceID == deviceID {
		claim.Current = previous
		if err := tx.Commit(); err != nil {
			return SessionClaim{}, err
		}
		return claim, nil
	}
	if err == nil {
		claim.Previous = &previous
	} else if !errors.Is(err, sql.ErrNoRows) {
		return SessionClaim{}, err
	}
	sessionID, err := randomToken()
	if err != nil {
		return SessionClaim{}, err
	}
	claim.Current = AccountSession{DeviceID: deviceID, SessionID: sessionID}
	claim.Changed = true

	rows, err := tx.Query(`
		SELECT ?, device_id, COALESCE(fcm_token, '')
		FROM devices
		WHERE user_id = ? AND device_id <> ?
		ORDER BY device_id
	`, login, userID, deviceID)
	if err != nil {
		return SessionClaim{}, err
	}
	for rows.Next() {
		var device Device
		if err := rows.Scan(&device.UserLogin, &device.DeviceID, &device.FCMToken); err != nil {
			_ = rows.Close()
			return SessionClaim{}, err
		}
		claim.RevokedDevices = append(claim.RevokedDevices, device)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return SessionClaim{}, err
	}
	if err := rows.Close(); err != nil {
		return SessionClaim{}, err
	}
	if _, err := tx.Exec("DELETE FROM devices WHERE user_id = ? AND device_id <> ?", userID, deviceID); err != nil {
		return SessionClaim{}, err
	}
	if _, err := tx.Exec(`
		INSERT INTO account_sessions(user_id, device_id, session_id, updated_at)
		VALUES(?, ?, ?, unixepoch())
		ON CONFLICT(user_id) DO UPDATE SET
			device_id = excluded.device_id,
			session_id = excluded.session_id,
			updated_at = excluded.updated_at
	`, userID, deviceID, sessionID); err != nil {
		return SessionClaim{}, err
	}
	if err := tx.QueryRow("SELECT updated_at FROM account_sessions WHERE user_id = ?", userID).Scan(&claim.Current.UpdatedAt); err != nil {
		return SessionClaim{}, err
	}
	if err := tx.Commit(); err != nil {
		return SessionClaim{}, err
	}
	return claim, nil
}
