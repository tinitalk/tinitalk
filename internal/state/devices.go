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
