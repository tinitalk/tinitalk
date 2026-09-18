package state

import (
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
)

const DefaultTemporaryPasswordTTL = 7 * 24 * time.Hour
const temporaryPasswordTTLSetting = "temporary_password_ttl"
const passwordHashPrefix = "$argon2id$v=19$m=19456,t=2,p=1$"

var (
	ErrInvalidCredentials       = errors.New("invalid credentials")
	ErrTemporaryPasswordExpired = errors.New("temporary password expired")
	ErrTemporaryPasswordLocked  = errors.New("temporary password locked")
	ErrInvalidPassword          = errors.New("password must contain 8 to 128 characters")
	ErrAuthBusy                 = errors.New("authentication busy")
	// Bound memory/CPU even when attackers use many different logins. No queue of
	// expensive work, and no password hashing while holding SQLite's writer lock.
	passwordWork = make(chan struct{}, 2)
)

type PasswordRetryError struct{ RetryAfter time.Duration }

func (e *PasswordRetryError) Error() string { return "password retry later" }

type PasswordLoginResult struct {
	Token            string `json:"token,omitempty"`
	PasswordRequired bool   `json:"password_required"`
	Rotated          bool   `json:"-"`
}

type passwordRecord struct {
	ID         int64
	Hash       sql.NullString
	Expires    sql.NullInt64
	Failures   int
	RetryAfter sql.NullInt64
	Disabled   bool
}

type passwordQuerier interface{ QueryRow(string, ...any) *sql.Row }

func loadPassword(q passwordQuerier, login string) (passwordRecord, error) {
	var record passwordRecord
	err := q.QueryRow(`SELECT id, password_hash, password_expires_at,
		password_failed_attempts, password_retry_after, disabled FROM users WHERE login=?`, login).
		Scan(&record.ID, &record.Hash, &record.Expires, &record.Failures, &record.RetryAfter, &record.Disabled)
	return record, err
}

func (db *DB) TemporaryPasswordTTL() (time.Duration, error) {
	value, err := db.Setting(temporaryPasswordTTLSetting)
	if err != nil {
		return 0, err
	}
	if value == "" {
		return DefaultTemporaryPasswordTTL, nil
	}
	ttl, err := time.ParseDuration(value)
	if err != nil || ttl < time.Second {
		return 0, errors.New("invalid temporary password TTL setting")
	}
	return ttl, nil
}

func (db *DB) SetTemporaryPasswordTTL(ttl time.Duration) error {
	if ttl < time.Second {
		return errors.New("temporary password TTL must be at least 1s")
	}
	return db.SetSetting(temporaryPasswordTTLSetting, ttl.String())
}

// AddUserWithPassword creates no access token: only the owner who chooses a
// personal password obtains API access. AddUser is retained for legacy fixtures.
func (db *DB) AddUserWithPassword(login, displayName string) (string, error) {
	if login == "" || displayName == "" {
		return "", errors.New("login and display name are required")
	}
	return db.issueTemporaryPassword(login, displayName, true)
}

func (db *DB) ResetPassword(login string) (string, error) {
	return db.issueTemporaryPassword(login, "", false)
}

func (db *DB) issueTemporaryPassword(login, displayName string, create bool) (string, error) {
	if !beginPasswordWork() {
		return "", ErrAuthBusy
	}
	defer endPasswordWork()
	ttl, err := db.TemporaryPasswordTTL()
	if err != nil {
		return "", err
	}
	number, err := rand.Int(rand.Reader, big.NewInt(100000000))
	if err != nil {
		return "", err
	}
	code := fmt.Sprintf("%08d", number.Int64())
	hash, err := newPasswordHash(code)
	if err != nil {
		return "", err
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	var id int64
	if create {
		result, err := tx.Exec("INSERT INTO users(login,display_name) VALUES(?,?)", login, displayName)
		if err != nil {
			return "", err
		}
		id, err = result.LastInsertId()
		if err != nil {
			return "", err
		}
	} else if err := tx.QueryRow("SELECT id FROM users WHERE login=?", login).Scan(&id); err != nil {
		return "", err
	}
	if _, err := tx.Exec(`UPDATE users SET password_hash=?, password_expires_at=?,
		password_failed_attempts=0,password_retry_after=NULL WHERE id=?`, hash, time.Now().Add(ttl).Unix(), id); err != nil {
		return "", err
	}
	if err := revokePasswordAccess(tx, id); err != nil {
		return "", err
	}
	return code, tx.Commit()
}

// PasswordLogin exchanges a personal password for a token, checks a temporary
// password without granting access, or imports an existing token unchanged.
func (db *DB) PasswordLogin(login, password string) (PasswordLoginResult, error) {
	if len(password) == 43 {
		_, valid, err := db.Authenticate(login, password)
		if err != nil {
			return PasswordLoginResult{}, err
		}
		if valid {
			return PasswordLoginResult{Token: password}, nil
		}
	}
	return db.passwordOperation(login, password, "", false)
}

func (db *DB) ChangePassword(login, password, newPassword string) (PasswordLoginResult, error) {
	length := utf8.RuneCountInString(newPassword)
	if !utf8.ValidString(newPassword) || length < 8 || length > 128 {
		return PasswordLoginResult{}, ErrInvalidPassword
	}
	return db.passwordOperation(login, password, newPassword, true)
}

func (db *DB) passwordOperation(login, password, newPassword string, change bool) (PasswordLoginResult, error) {
	if len(login) > 256 || len(password) > 1024 || !utf8.ValidString(password) {
		return PasswordLoginResult{}, ErrInvalidCredentials
	}
	if !beginPasswordWork() {
		return PasswordLoginResult{}, ErrAuthBusy
	}
	defer endPasswordWork()
	record, err := loadPassword(db.read, login)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && record.Disabled) {
		dummyPasswordCheck(password)
		return PasswordLoginResult{}, ErrInvalidCredentials
	}
	if err != nil {
		return PasswordLoginResult{}, err
	}
	if err := passwordBlocked(record, time.Now().Unix()); err != nil {
		return PasswordLoginResult{}, err
	}
	valid := false
	if record.Hash.Valid {
		candidate := password
		if record.Expires.Valid {
			candidate = strings.ReplaceAll(strings.TrimSpace(candidate), " ", "")
		}
		valid, err = checkPasswordHash(record.Hash.String, candidate)
		if err != nil {
			return PasswordLoginResult{}, err
		}
	} else if change {
		_, valid, err = db.Authenticate(login, password)
		if err != nil {
			return PasswordLoginResult{}, err
		}
		if !valid {
			dummyPasswordCheck(password)
		}
	} else {
		dummyPasswordCheck(password)
	}
	var replacementHash string
	if valid && change {
		replacementHash, err = newPasswordHash(newPassword)
		if err != nil {
			return PasswordLoginResult{}, err
		}
	}
	tx, err := db.sql.Begin()
	if err != nil {
		return PasswordLoginResult{}, err
	}
	defer tx.Rollback()
	current, err := loadPassword(tx, login)
	if errors.Is(err, sql.ErrNoRows) {
		return PasswordLoginResult{}, ErrInvalidCredentials
	}
	if err != nil {
		return PasswordLoginResult{}, err
	}
	// A CLI reset or another setup may have replaced the credential during the
	// hash calculation. Never authorize against the superseded snapshot.
	if current.Disabled || current.ID != record.ID || current.Hash != record.Hash || current.Expires != record.Expires {
		return PasswordLoginResult{}, ErrInvalidCredentials
	}
	if err := passwordBlocked(current, time.Now().Unix()); err != nil {
		return PasswordLoginResult{}, err
	}
	if valid && !record.Hash.Valid {
		var count int
		if err := tx.QueryRow("SELECT count(*) FROM auth_tokens WHERE user_id=? AND token_sha256=? AND active=1", record.ID, hashToken(password)).Scan(&count); err != nil {
			return PasswordLoginResult{}, err
		}
		valid = count > 0
	}
	if !valid {
		if !current.Hash.Valid {
			return PasswordLoginResult{}, ErrInvalidCredentials
		}
		failureErr, err := recordPasswordFailure(tx, current)
		if err != nil {
			return PasswordLoginResult{}, err
		}
		if err := tx.Commit(); err != nil {
			return PasswordLoginResult{}, err
		}
		return PasswordLoginResult{}, failureErr
	}
	if record.Expires.Valid && !change {
		return PasswordLoginResult{PasswordRequired: true}, nil
	}
	if change {
		if _, err := tx.Exec("UPDATE users SET password_hash=?,password_expires_at=NULL WHERE id=?", replacementHash, record.ID); err != nil {
			return PasswordLoginResult{}, err
		}
	}
	if _, err := tx.Exec("UPDATE users SET password_failed_attempts=0,password_retry_after=NULL WHERE id=?", record.ID); err != nil {
		return PasswordLoginResult{}, err
	}
	if err := revokePasswordAccess(tx, record.ID); err != nil {
		return PasswordLoginResult{}, err
	}
	token, err := randomToken()
	if err != nil {
		return PasswordLoginResult{}, err
	}
	if _, err := tx.Exec("INSERT INTO auth_tokens(user_id,token_sha256) VALUES(?,?)", record.ID, hashToken(token)); err != nil {
		return PasswordLoginResult{}, err
	}
	if err := tx.Commit(); err != nil {
		return PasswordLoginResult{}, err
	}
	return PasswordLoginResult{Token: token, Rotated: true}, nil
}

func passwordBlocked(record passwordRecord, now int64) error {
	if record.Expires.Valid {
		if record.Failures >= 5 {
			return ErrTemporaryPasswordLocked
		}
		if record.Expires.Int64 <= now {
			return ErrTemporaryPasswordExpired
		}
	} else if record.RetryAfter.Valid && record.RetryAfter.Int64 > now {
		return &PasswordRetryError{RetryAfter: time.Duration(record.RetryAfter.Int64-now) * time.Second}
	}
	return nil
}

func recordPasswordFailure(tx *sql.Tx, record passwordRecord) (error, error) {
	failures := min(record.Failures+1, 35)
	var retry any
	var result error = ErrInvalidCredentials
	if record.Expires.Valid {
		if failures >= 5 {
			result = ErrTemporaryPasswordLocked
		}
	} else if failures >= 5 {
		seconds := min(int64(30)<<min(failures-5, 5), 900)
		retry = time.Now().Unix() + seconds
		result = &PasswordRetryError{RetryAfter: time.Duration(seconds) * time.Second}
	}
	_, err := tx.Exec("UPDATE users SET password_failed_attempts=?,password_retry_after=? WHERE id=?", failures, retry, record.ID)
	return result, err
}

// Logout ends only the presented session, checking its ownership in the same
// transaction as revocation. Until a legacy account sets a password, its token
// is its only reusable credential and must remain valid for the next login.
func (db *DB) Logout(login, token, sessionID string) error {
	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var id int64
	var hasPassword bool
	err = tx.QueryRow(`SELECT u.id, u.password_hash IS NOT NULL FROM users u JOIN auth_tokens t ON t.user_id=u.id
		WHERE u.login=? AND u.disabled=0 AND t.token_sha256=? AND t.active=1`, login, hashToken(token)).Scan(&id, &hasPassword)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrInvalidCredentials
	}
	if err != nil {
		return err
	}
	if sessionID == "" {
		return ErrSessionReplaced
	}
	var currentSessionID string
	err = tx.QueryRow("SELECT session_id FROM account_sessions WHERE user_id=?", id).Scan(&currentSessionID)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrSessionReplaced
	}
	if err != nil {
		return err
	}
	if currentSessionID != sessionID {
		return ErrSessionReplaced
	}
	if hasPassword {
		err = revokePasswordAccess(tx, id)
	} else {
		err = endAccountSession(tx, id)
	}
	if err != nil {
		return err
	}
	return tx.Commit()
}

func revokePasswordAccess(tx *sql.Tx, id int64) error {
	if _, err := tx.Exec("DELETE FROM auth_tokens WHERE user_id=?", id); err != nil {
		return err
	}
	return endAccountSession(tx, id)
}

func endAccountSession(tx *sql.Tx, id int64) error {
	if _, err := tx.Exec("DELETE FROM devices WHERE user_id=?", id); err != nil {
		return err
	}
	// Keep a managed tombstone, rather than deleting the session and accidentally
	// making stale sockets look like unrestricted legacy clients.
	session, err := randomToken()
	if err != nil {
		return err
	}
	_, err = tx.Exec(`INSERT INTO account_sessions(user_id,device_id,session_id) VALUES(?,'revoked',?)
		ON CONFLICT(user_id) DO UPDATE SET device_id='revoked',session_id=excluded.session_id,updated_at=unixepoch()`, id, session)
	return err
}

func beginPasswordWork() bool {
	select {
	case passwordWork <- struct{}{}:
		return true
	default:
		return false
	}
}
func endPasswordWork() { <-passwordWork }

func newPasswordHash(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	hash := argon2.IDKey([]byte(password), salt, 2, 19*1024, 1, 32)
	return passwordHashPrefix + base64.RawStdEncoding.EncodeToString(salt) + "$" + base64.RawStdEncoding.EncodeToString(hash), nil
}

func checkPasswordHash(encoded, password string) (bool, error) {
	if !strings.HasPrefix(encoded, passwordHashPrefix) {
		return false, errors.New("unsupported password hash")
	}
	parts := strings.Split(strings.TrimPrefix(encoded, passwordHashPrefix), "$")
	if len(parts) != 2 {
		return false, errors.New("invalid password hash")
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[0])
	if err != nil || len(salt) != 16 {
		return false, errors.New("invalid password salt")
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[1])
	if err != nil || len(want) != 32 {
		return false, errors.New("invalid password hash")
	}
	hash := argon2.IDKey([]byte(password), salt, 2, 19*1024, 1, 32)
	return subtle.ConstantTimeCompare(hash, want) == 1, nil
}

func dummyPasswordCheck(password string) {
	_ = argon2.IDKey([]byte(password), []byte("tinitalk-dummy-v1"), 2, 19*1024, 1, 32)
}
