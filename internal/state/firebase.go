package state

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"

	"tinitalk/internal/firebaseconfig"
)

const firebaseAndroidConfigSetting = "firebase_android_config"

func (db *DB) FirebaseConfig() (firebaseconfig.Config, error) {
	raw, err := db.Setting(firebaseAndroidConfigSetting)
	return parseFirebaseConfig(raw, err)
}

func parseFirebaseConfig(raw string, err error) (firebaseconfig.Config, error) {
	if err != nil || raw == "" {
		return firebaseconfig.Config{}, err
	}
	var config firebaseconfig.Config
	if err := json.Unmarshal([]byte(raw), &config); err != nil {
		return firebaseconfig.Config{}, fmt.Errorf("parse stored Android Firebase configuration: %w", err)
	}
	if err := firebaseconfig.Validate(config); err != nil {
		return firebaseconfig.Config{}, fmt.Errorf("validate stored Android Firebase configuration: %w", err)
	}
	return config, nil
}

func (db *DB) initFirebase(fcmServiceAccount, androidConfig []byte) error {
	if len(fcmServiceAccount) == 0 && len(androidConfig) == 0 {
		return nil
	}
	ctx := context.Background()
	conn, err := db.sql.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	if _, err := conn.ExecContext(ctx, "BEGIN IMMEDIATE"); err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_, _ = conn.ExecContext(ctx, "ROLLBACK")
		}
	}()
	var storedServiceAccount []byte
	err = conn.QueryRowContext(ctx, "SELECT value FROM secrets WHERE key = ?", "fcm_service_account").Scan(&storedServiceAccount)
	if errors.Is(err, sql.ErrNoRows) {
		storedServiceAccount = nil
	} else if err != nil {
		return err
	}
	var storedConfigRaw string
	err = conn.QueryRowContext(ctx, "SELECT value FROM settings WHERE key = ?", firebaseAndroidConfigSetting).Scan(&storedConfigRaw)
	if errors.Is(err, sql.ErrNoRows) {
		storedConfigRaw = ""
		err = nil
	}
	storedConfig, err := parseFirebaseConfig(storedConfigRaw, err)
	if err != nil {
		return err
	}
	effectiveServiceAccount := storedServiceAccount
	if len(fcmServiceAccount) > 0 {
		effectiveServiceAccount = fcmServiceAccount
	}
	effectiveConfig := storedConfig
	if len(androidConfig) > 0 {
		effectiveConfig, err = firebaseconfig.Parse(androidConfig)
		if err != nil {
			return err
		}
	}
	if len(effectiveServiceAccount) == 0 || effectiveConfig.ConfigID == "" {
		return errors.New("Firebase service account and Android configuration are both required")
	}
	if err := firebaseconfig.ValidatePair(effectiveServiceAccount, effectiveConfig); err != nil {
		return err
	}
	if len(storedServiceAccount) > 0 {
		if storedProjectID, err := firebaseconfig.ProjectIDFromServiceAccount(storedServiceAccount); err == nil && storedProjectID != effectiveConfig.ProjectID {
			return errors.New("Firebase project_id cannot be changed")
		}
	}
	if storedConfig.ConfigID != "" && storedConfig.ProjectID != effectiveConfig.ProjectID {
		return errors.New("Firebase project_id cannot be changed")
	}
	storedConfigJSON, err := json.Marshal(effectiveConfig)
	if err != nil {
		return err
	}
	if len(fcmServiceAccount) > 0 {
		if _, err := conn.ExecContext(ctx, `
			INSERT INTO secrets(key, value) VALUES('fcm_service_account', ?)
			ON CONFLICT(key) DO UPDATE SET value=excluded.value
		`, fcmServiceAccount); err != nil {
			return err
		}
	}
	if len(androidConfig) > 0 {
		if _, err := conn.ExecContext(ctx, `
			INSERT INTO settings(key, value) VALUES(?, ?)
			ON CONFLICT(key) DO UPDATE SET value=excluded.value
		`, firebaseAndroidConfigSetting, string(storedConfigJSON)); err != nil {
			return err
		}
	}
	if _, err := conn.ExecContext(ctx, "COMMIT"); err != nil {
		return err
	}
	committed = true
	return nil
}
