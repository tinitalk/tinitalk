package state

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"

	webpush "github.com/ergochat/webpush-go/v2"
)

const webPushVAPIDSecret = "webpush_vapid"

type WebPushVAPID struct {
	Keys      *webpush.VAPIDKeys
	PublicKey string
	ConfigID  string
}

func (db *DB) EnsureWebPushVAPID() (WebPushVAPID, error) {
	raw, err := db.Secret(webPushVAPIDSecret)
	if err != nil {
		return WebPushVAPID{}, err
	}
	if len(raw) == 0 {
		keys, err := webpush.GenerateVAPIDKeys()
		if err != nil {
			return WebPushVAPID{}, fmt.Errorf("generate WebPush VAPID keys: %w", err)
		}
		raw, err = json.Marshal(keys)
		if err != nil {
			return WebPushVAPID{}, fmt.Errorf("encode WebPush VAPID keys: %w", err)
		}
		if _, err := db.sql.Exec("INSERT INTO secrets(key, value) VALUES(?, ?)", webPushVAPIDSecret, raw); err != nil {
			return WebPushVAPID{}, err
		}
	}

	keys := new(webpush.VAPIDKeys)
	if err := json.Unmarshal(raw, keys); err != nil {
		return WebPushVAPID{}, fmt.Errorf("parse stored WebPush VAPID keys: %w", err)
	}
	publicKey := keys.PublicKeyString()
	if publicKey == "" {
		return WebPushVAPID{}, errors.New("stored WebPush VAPID public key is empty")
	}
	sum := sha256.Sum256([]byte(publicKey))
	return WebPushVAPID{
		Keys:      keys,
		PublicKey: publicKey,
		ConfigID:  "sha256:" + hex.EncodeToString(sum[:]),
	}, nil
}
