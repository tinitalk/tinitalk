package state

import (
	"crypto/subtle"
)

func (db *DB) Authenticate(login, token string) (User, bool, error) {
	rows, err := db.read.Query(`
		SELECT u.login, u.disabled, t.token_sha256,
			u.password_hash IS NOT NULL AND u.password_expires_at IS NULL
		FROM users u
		JOIN auth_tokens t ON t.user_id = u.id
		WHERE u.login = ? AND t.active = 1
	`, login)
	if err != nil {
		return User{}, false, err
	}
	defer rows.Close()

	tokenHash := hashToken(token)
	var user User
	ok := false
	for rows.Next() {
		var verifier string
		if err := rows.Scan(&user.Login, &user.Disabled, &verifier, &user.PasswordSet); err != nil {
			return User{}, false, err
		}
		if subtle.ConstantTimeCompare([]byte(verifier), []byte(tokenHash)) == 1 && !user.Disabled {
			ok = true
		}
	}
	if err := rows.Err(); err != nil {
		return User{}, false, err
	}
	return user, ok, nil
}
