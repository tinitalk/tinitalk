package state

type Device struct {
	UserLogin string
	DeviceID  string
	FCMToken  string
}

func (db *DB) UpsertDevice(login, deviceID, fcmToken string) error {
	var userID int64
	if err := db.sql.QueryRow("SELECT id FROM users WHERE login = ?", login).Scan(&userID); err != nil {
		return err
	}
	_, err := db.sql.Exec(`
		INSERT INTO devices(user_id, device_id, fcm_token, updated_at) VALUES(?, ?, ?, unixepoch())
		ON CONFLICT(user_id, device_id) DO UPDATE SET fcm_token=excluded.fcm_token, updated_at=unixepoch()
	`, userID, deviceID, fcmToken)
	return err
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
