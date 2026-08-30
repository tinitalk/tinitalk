package state

import (
	"database/sql"
	"errors"
)

type PushTargetKind string

const (
	KindToken PushTargetKind = "token"
	KindFID   PushTargetKind = "fid"
)

type PushTarget struct {
	Kind     PushTargetKind
	Value    string
	ConfigID string
}

func (target PushTarget) validate() error {
	switch target.Kind {
	case KindToken:
		if target.Value == "" || target.ConfigID != "" {
			return errors.New("invalid token push target")
		}
	case KindFID:
		if target.Value == "" || target.ConfigID == "" {
			return errors.New("invalid FID push target")
		}
	default:
		return errors.New("unsupported push target kind")
	}
	return nil
}

type Device struct {
	UserLogin  string
	DeviceID   string
	PushTarget PushTarget
}

// UpsertDevice keeps the legacy HTTP registration path on a token target.
func (db *DB) UpsertDevice(login, deviceID, fcmToken string) error {
	return db.UpsertPushTarget(login, deviceID, PushTarget{Kind: KindToken, Value: fcmToken})
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

// UpsertAuthenticatedDevice keeps the legacy HTTP registration path on a token target.
func (db *DB) UpsertAuthenticatedDevice(login, sessionID, deviceID, fcmToken string) error {
	return db.UpsertAuthenticatedPushTarget(login, sessionID, deviceID, PushTarget{Kind: KindToken, Value: fcmToken})
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
		WHERE (device_id = ? OR (push_kind = ? AND push_value = ?))
			AND NOT (user_id = ? AND device_id = ?)
	`, deviceID, target.Kind, target.Value, userID, deviceID); err != nil {
		return err
	}
	if _, err := tx.Exec(`
		INSERT INTO devices(user_id, device_id, push_kind, push_value, config_id, updated_at)
		VALUES(?, ?, ?, ?, ?, unixepoch())
		ON CONFLICT(user_id, device_id) DO UPDATE SET
			push_kind = excluded.push_kind,
			push_value = excluded.push_value,
			config_id = excluded.config_id,
			updated_at = unixepoch()
	`, userID, deviceID, target.Kind, target.Value, nullableConfigID(target)); err != nil {
		return err
	}
	return nil
}

func nullableConfigID(target PushTarget) any {
	if target.Kind == KindToken {
		return nil
	}
	return target.ConfigID
}

func (db *DB) PushTargetsForUser(login string) ([]Device, error) {
	rows, err := db.sql.Query(`
		SELECT u.login, d.device_id, d.push_kind, d.push_value, d.config_id
		FROM devices d
		JOIN users u ON u.id = d.user_id
		WHERE u.login = ? AND u.disabled = 0 AND d.push_kind IS NOT NULL
	`, login)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var devices []Device
	for rows.Next() {
		var device Device
		var configID sql.NullString
		if err := rows.Scan(&device.UserLogin, &device.DeviceID, &device.PushTarget.Kind, &device.PushTarget.Value, &configID); err != nil {
			return nil, err
		}
		if configID.Valid {
			device.PushTarget.ConfigID = configID.String
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
		SET push_kind = NULL, push_value = NULL, config_id = NULL, updated_at = unixepoch()
		WHERE push_kind = ? AND push_value = ?
	`, target.Kind, target.Value)
	return err
}
