package state

import (
	"database/sql"
	"errors"
)

type PushTarget struct {
	Subscription string
	ConfigID     string
}

func (target PushTarget) validate() error {
	if target.Subscription == "" || target.ConfigID == "" {
		return errors.New("invalid WebPush target")
	}
	return nil
}

type Device struct {
	UserLogin  string
	DeviceID   string
	PushTarget PushTarget
}

func (db *DB) UpsertPushTarget(login, deviceID string, target PushTarget) error {
	if err := target.validate(); err != nil {
		return err
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var userID int64
	if err := tx.QueryRow("SELECT id FROM users WHERE login = ?", login).Scan(&userID); err != nil {
		return err
	}
	if err := upsertPushTarget(tx, userID, deviceID, target); err != nil {
		return err
	}
	return tx.Commit()
}

func (db *DB) UpsertAuthenticatedPushTarget(login, sessionID, deviceID string, target PushTarget) error {
	if err := target.validate(); err != nil {
		return err
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var userID int64
	var activeDeviceID, activeSessionID sql.NullString
	if err := tx.QueryRow(`
		SELECT user.id, session.device_id, session.session_id
		FROM users user
		LEFT JOIN account_sessions session ON session.user_id = user.id
		WHERE user.login = ?
	`, login).Scan(&userID, &activeDeviceID, &activeSessionID); err != nil {
		return err
	}
	if activeSessionID.Valid {
		if activeSessionID.String != sessionID || !activeDeviceID.Valid || activeDeviceID.String != deviceID {
			return ErrSessionReplaced
		}
	} else if sessionID != "" {
		return ErrSessionReplaced
	}
	if err := upsertPushTarget(tx, userID, deviceID, target); err != nil {
		return err
	}
	return tx.Commit()
}

func upsertPushTarget(tx *sql.Tx, userID int64, deviceID string, target PushTarget) error {
	if _, err := tx.Exec(`
		DELETE FROM devices
		WHERE (device_id = ? OR webpush_subscription = ?)
			AND NOT (user_id = ? AND device_id = ?)
	`, deviceID, target.Subscription, userID, deviceID); err != nil {
		return err
	}
	_, err := tx.Exec(`
		INSERT INTO devices(user_id, device_id, webpush_subscription, webpush_config_id, updated_at)
		VALUES(?, ?, ?, ?, unixepoch())
		ON CONFLICT(user_id, device_id) DO UPDATE SET
			webpush_subscription = excluded.webpush_subscription,
			webpush_config_id = excluded.webpush_config_id,
			updated_at = unixepoch()
	`, userID, deviceID, target.Subscription, target.ConfigID)
	return err
}

func (db *DB) PushTargetsForUser(login string) ([]Device, error) {
	rows, err := db.sql.Query(`
		SELECT u.login, d.device_id, d.webpush_subscription, d.webpush_config_id
		FROM devices d
		JOIN users u ON u.id = d.user_id
		WHERE u.login = ? AND u.disabled = 0 AND d.webpush_subscription IS NOT NULL
	`, login)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var devices []Device
	for rows.Next() {
		var device Device
		if err := rows.Scan(&device.UserLogin, &device.DeviceID, &device.PushTarget.Subscription, &device.PushTarget.ConfigID); err != nil {
			return nil, err
		}
		devices = append(devices, device)
	}
	return devices, rows.Err()
}

func (db *DB) DisablePushTarget(target PushTarget) error {
	if err := target.validate(); err != nil {
		return err
	}
	_, err := db.sql.Exec(`
		UPDATE devices
		SET webpush_subscription = NULL, webpush_config_id = NULL, updated_at = unixepoch()
		WHERE webpush_subscription = ? AND webpush_config_id = ?
	`, target.Subscription, target.ConfigID)
	return err
}
