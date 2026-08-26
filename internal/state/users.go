package state

import "fmt"

type User struct {
	Login       string
	DisplayName string
	Disabled    bool
}

func (db *DB) AddUser(login, displayName string) (string, error) {
	if login == "" || displayName == "" {
		return "", fmt.Errorf("login and display name are required")
	}
	token, err := randomToken()
	if err != nil {
		return "", err
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return "", err
	}
	result, err := tx.Exec("INSERT INTO users(login, display_name) VALUES(?, ?)", login, displayName)
	if err != nil {
		_ = tx.Rollback()
		return "", err
	}
	userID, err := result.LastInsertId()
	if err != nil {
		_ = tx.Rollback()
		return "", err
	}
	if _, err := tx.Exec("INSERT INTO auth_tokens(user_id, token_sha256) VALUES(?, ?)", userID, hashToken(token)); err != nil {
		_ = tx.Rollback()
		return "", err
	}
	if _, err := tx.Exec(`
		INSERT INTO user_contacts(owner_user_id, contact_user_id)
		SELECT id, ? FROM users WHERE id <> ?
	`, userID, userID); err != nil {
		_ = tx.Rollback()
		return "", err
	}
	if _, err := tx.Exec(`
		INSERT INTO user_contacts(owner_user_id, contact_user_id)
		SELECT ?, id FROM users WHERE id <> ?
	`, userID, userID); err != nil {
		_ = tx.Rollback()
		return "", err
	}
	return token, tx.Commit()
}

func (db *DB) ListUsers() ([]User, error) {
	rows, err := db.sql.Query("SELECT login, display_name, disabled FROM users ORDER BY login")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []User
	for rows.Next() {
		var user User
		if err := rows.Scan(&user.Login, &user.DisplayName, &user.Disabled); err != nil {
			return nil, err
		}
		users = append(users, user)
	}
	return users, rows.Err()
}

func (db *DB) DisplayName(login string) (string, error) {
	var displayName string
	err := db.sql.QueryRow("SELECT display_name FROM users WHERE login = ? AND disabled = 0", login).Scan(&displayName)
	return displayName, err
}

func (db *DB) DisableUser(login string) error {
	result, err := db.sql.Exec("UPDATE users SET disabled = 1 WHERE login = ?", login)
	if err != nil {
		return err
	}
	return requireAffected(result, "user not found")
}

func (db *DB) DeleteUser(login string) error {
	result, err := db.sql.Exec("DELETE FROM users WHERE login = ?", login)
	if err != nil {
		return err
	}
	return requireAffected(result, "user not found")
}

func (db *DB) RotateToken(login string) (string, error) {
	token, err := randomToken()
	if err != nil {
		return "", err
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return "", err
	}
	var userID int64
	if err := tx.QueryRow("SELECT id FROM users WHERE login = ?", login).Scan(&userID); err != nil {
		_ = tx.Rollback()
		return "", err
	}
	if _, err := tx.Exec("UPDATE auth_tokens SET active = 0 WHERE user_id = ?", userID); err != nil {
		_ = tx.Rollback()
		return "", err
	}
	if _, err := tx.Exec("INSERT INTO auth_tokens(user_id, token_sha256) VALUES(?, ?)", userID, hashToken(token)); err != nil {
		_ = tx.Rollback()
		return "", err
	}
	return token, tx.Commit()
}
