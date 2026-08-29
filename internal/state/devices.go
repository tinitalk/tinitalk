package state

import "database/sql"

type Device struct {
	UserLogin string
	DeviceID  string
	FCMToken  string
}

func (db *DB) UpsertDevice(login, deviceID, fcmToken string) error {
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var userID int64
	if err := tx.QueryRow("SELECT id FROM users WHERE login = ?", login).Scan(&userID); err != nil {
		return err
	}
	if err := upsertDevice(tx, userID, deviceID, fcmToken); err != nil {
		return err
	}
	return tx.Commit()
}

func (db *DB) UpsertAuthenticatedDevice(login, sessionID, deviceID, fcmToken string) error {
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
	if err := upsertDevice(tx, userID, deviceID, fcmToken); err != nil {
		return err
	}
	return tx.Commit()
}

func upsertDevice(tx *sql.Tx, userID int64, deviceID, fcmToken string) error {
	if _, err := tx.Exec(`
		DELETE FROM devices
		WHERE (device_id = ? OR fcm_token = ?)
			AND NOT (user_id = ? AND device_id = ?)
	`, deviceID, fcmToken, userID, deviceID); err != nil {
		return err
	}
	if _, err := tx.Exec(`
		INSERT INTO devices(user_id, device_id, fcm_token, updated_at) VALUES(?, ?, ?, unixepoch())
		ON CONFLICT(user_id, device_id) DO UPDATE SET fcm_token=excluded.fcm_token, updated_at=unixepoch()
	`, userID, deviceID, fcmToken); err != nil {
		return err
	}
	return nil
}

func (db *DB) TokensForUser(login string) ([]Device, error) {
	rows, err := db.sql.Query(`
		SELECT u.login, d.device_id, d.fcm_token
		FROM devices d
		JOIN users u ON u.id = d.user_id
		WHERE u.login = ? AND d.fcm_token IS NOT NULL AND d.fcm_token != ''
	`, login)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var devices []Device
	for rows.Next() {
		var device Device
		if err := rows.Scan(&device.UserLogin, &device.DeviceID, &device.FCMToken); err != nil {
			return nil, err
		}
		devices = append(devices, device)
	}
	return devices, rows.Err()
}

func (db *DB) DisableToken(token string) error {
	_, err := db.sql.Exec("UPDATE devices SET fcm_token = NULL, updated_at = unixepoch() WHERE fcm_token = ?", token)
	return err
}
