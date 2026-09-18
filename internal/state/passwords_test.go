package state

import (
	"database/sql"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPasswordMigrationPreservesLegacyTokenAndSession(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	schema := freshSchemaSQL
	for _, column := range []string{
		"\tpassword_hash TEXT,\n", "\tpassword_expires_at INTEGER,\n",
		"\tpassword_failed_attempts INTEGER NOT NULL DEFAULT 0,\n", "\tpassword_retry_after INTEGER,\n",
	} {
		schema = strings.Replace(schema, column, "", 1)
	}
	if _, err := old.Exec(schema); err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec(`INSERT INTO users(id,login,display_name) VALUES(1,'alice','Alice');
		INSERT INTO account_sessions(user_id,device_id,session_id) VALUES(1,'phone','existing-session');
		PRAGMA user_version=10;`); err != nil {
		t.Fatal(err)
	}
	token, err := randomToken()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec("INSERT INTO auth_tokens(user_id,token_sha256) VALUES(1,?)", hashToken(token)); err != nil {
		t.Fatal(err)
	}
	old.Close()
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, ok, err := db.Authenticate("alice", token); err != nil || !ok {
		t.Fatalf("migration lost token: %v", err)
	}
	session, managed, err := db.CurrentSession("alice")
	if err != nil || !managed || session.SessionID != "existing-session" {
		t.Fatalf("migration lost session: %v", err)
	}
	if _, err := db.ChangePassword("alice", token, testPersonalPassword); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := db.Authenticate("alice", token); ok {
		t.Fatal("legacy token survived migration to personal password")
	}
}

func TestLegacyLogoutPreservesCredentialUntilPasswordSetup(t *testing.T) {
	db := testStateDB(t)
	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		login, err := db.PasswordLogin("alice", token)
		if err != nil || login.Token != token || login.PasswordRequired || login.Rotated {
			t.Fatalf("legacy login = %+v, %v", login, err)
		}
		claim, err := db.ClaimSessionWithToken("alice", token, "phone", nil)
		if err != nil {
			t.Fatal(err)
		}
		if err := db.Logout("alice", "wrong-token", claim.Current.SessionID); !errors.Is(err, ErrInvalidCredentials) {
			t.Fatalf("invalid logout = %v", err)
		}
		for _, invalidSession := range []string{"", "wrong-session"} {
			if err := db.Logout("alice", token, invalidSession); !errors.Is(err, ErrSessionReplaced) {
				t.Fatalf("invalid session logout = %v", err)
			}
		}
		if current, _, err := db.CurrentSession("alice"); err != nil || current != claim.Current {
			t.Fatalf("invalid logout changed session: %+v, %v", current, err)
		}
		if err := db.Logout("alice", token, claim.Current.SessionID); err != nil {
			t.Fatal(err)
		}
		current, managed, err := db.CurrentSession("alice")
		if err != nil || !managed || current.SessionID == claim.Current.SessionID {
			t.Fatalf("logout did not end session: %+v, %v", current, err)
		}
		if user, valid, err := db.Authenticate("alice", token); err != nil || !valid || user.PasswordSet {
			t.Fatalf("logout changed legacy credential: valid=%v, err=%v", valid, err)
		}
	}
	if _, err := db.ChangePassword("alice", token, testPersonalPassword); err != nil {
		t.Fatal(err)
	}
	if _, err := db.PasswordLogin("alice", token); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("old token survived password setup: %v", err)
	}
}

func TestPasswordFailedAttemptsSurviveReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	code, err := db.AddUserWithPassword("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	for range 5 {
		_, _ = db.PasswordLogin("alice", "wrong")
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.PasswordLogin("alice", code); !errors.Is(err, ErrTemporaryPasswordLocked) {
		t.Fatalf("restart restored exhausted code: %v", err)
	}
}

func TestTemporaryPasswordConcurrentConsumption(t *testing.T) {
	db := testStateDB(t)
	code, err := db.AddUserWithPassword("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	results := make(chan error, 2)
	for range 2 {
		go func() { <-start; _, err := db.ChangePassword("alice", code, testPersonalPassword); results <- err }()
	}
	close(start)
	successes := 0
	for range 2 {
		err := <-results
		if err == nil {
			successes++
		} else if !errors.Is(err, ErrInvalidCredentials) {
			t.Fatalf("concurrent setup error: %v", err)
		}
	}
	if successes != 1 {
		t.Fatalf("consumed same code %d times", successes)
	}
}

func TestPasswordHashWorkIsBounded(t *testing.T) {
	db := testStateDB(t)
	for range cap(passwordWork) {
		passwordWork <- struct{}{}
	}
	defer func() {
		for range cap(passwordWork) {
			<-passwordWork
		}
	}()
	if _, err := db.PasswordLogin("missing", "wrong"); !errors.Is(err, ErrAuthBusy) {
		t.Fatalf("saturated auth = %v", err)
	}
}

func TestRevokedTokenCannotClaimSession(t *testing.T) {
	db := testStateDB(t)
	token, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ClaimSessionWithToken("alice", token, "phone", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ResetPassword("alice"); err != nil {
		t.Fatal(err)
	}
	before, _, err := db.CurrentSession("alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ClaimSessionWithToken("alice", token, "old-phone", nil); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("revoked claim = %v", err)
	}
	after, _, err := db.CurrentSession("alice")
	if err != nil || after.SessionID != before.SessionID {
		t.Fatalf("revoked token changed session: %v", err)
	}
}

func TestPasswordFailureBackoffCap(t *testing.T) {
	db := testStateDB(t)
	legacy, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ChangePassword("alice", legacy, testPersonalPassword); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("UPDATE users SET password_failed_attempts=20 WHERE login='alice'"); err != nil {
		t.Fatal(err)
	}
	_, err = db.PasswordLogin("alice", "wrong")
	var pause *PasswordRetryError
	if !errors.As(err, &pause) || pause.RetryAfter != 15*time.Minute {
		t.Fatalf("max backoff = %v", err)
	}
}

const testPersonalPassword = "my personal password"

func TestTemporaryPasswordSetupAndRotation(t *testing.T) {
	db := testStateDB(t)
	code, err := db.AddUserWithPassword("alice", "Administrator-only name")
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != 8 || strings.Trim(code, "0123456789") != "" {
		t.Fatalf("invalid code format")
	}
	if _, ok, err := db.Authenticate("alice", code); err != nil || ok {
		t.Fatalf("temporary password grants API access: %v %v", ok, err)
	}
	for range 2 {
		result, err := db.PasswordLogin("alice", code[:4]+" "+code[4:])
		if err != nil || !result.PasswordRequired || result.Token != "" {
			t.Fatalf("setup result = %+v, %v", result, err)
		}
	}
	result, err := db.ChangePassword("alice", code, testPersonalPassword)
	if err != nil || result.PasswordRequired || !result.Rotated || len(result.Token) != 43 {
		t.Fatalf("setup failed: %v", err)
	}
	if user, ok, err := db.Authenticate("alice", result.Token); err != nil || !ok || !user.PasswordSet {
		t.Fatalf("new token invalid: %v", err)
	}
	if _, err := db.PasswordLogin("alice", code); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("code reused: %v", err)
	}
	imported, err := db.PasswordLogin("alice", result.Token)
	if err != nil || imported.Token != result.Token || imported.Rotated {
		t.Fatalf("token import rotated credentials: %v", err)
	}
	if _, err := db.ChangePassword("alice", result.Token, "another long password"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("token changed existing password: %v", err)
	}
	claim, err := db.ClaimSession("alice", "phone")
	if err != nil {
		t.Fatal(err)
	}
	next, err := db.PasswordLogin("alice", testPersonalPassword)
	if err != nil || next.Token == result.Token {
		t.Fatalf("login rotation failed: %v", err)
	}
	if _, ok, _ := db.Authenticate("alice", result.Token); ok {
		t.Fatal("old token survived login")
	}
	session, managed, err := db.CurrentSession("alice")
	if err != nil || !managed || session.SessionID == claim.Current.SessionID {
		t.Fatalf("old session survived login: %v", err)
	}
	if err := db.Logout("alice", result.Token, claim.Current.SessionID); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("stale logout = %v", err)
	}
	if _, ok, _ := db.Authenticate("alice", next.Token); !ok {
		t.Fatal("stale logout revoked new token")
	}
	nextClaim, err := db.ClaimSessionWithToken("alice", next.Token, "phone", nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Logout("alice", next.Token, nextClaim.Current.SessionID); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := db.Authenticate("alice", next.Token); ok {
		t.Fatal("logout token still accepted")
	}
}

func TestTemporaryPasswordAttemptsExpiryAndReset(t *testing.T) {
	db := testStateDB(t)
	code, err := db.AddUserWithPassword("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 5; i++ {
		_, err := db.PasswordLogin("alice", "wrong")
		want := ErrInvalidCredentials
		if i == 5 {
			want = ErrTemporaryPasswordLocked
		}
		if !errors.Is(err, want) {
			t.Fatalf("attempt %d: %v", i, err)
		}
		if i == 2 { // Checking a correct code must not replenish its attempt budget.
			if _, err := db.PasswordLogin("alice", code); err != nil {
				t.Fatal(err)
			}
		}
	}
	if _, err := db.ChangePassword("alice", code, testPersonalPassword); !errors.Is(err, ErrTemporaryPasswordLocked) {
		t.Fatalf("locked code consumed: %v", err)
	}
	code, err = db.ResetPassword("alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.PasswordLogin("alice", code); err != nil {
		t.Fatal(err)
	}
	if _, err := db.sql.Exec("UPDATE users SET password_expires_at = unixepoch()-1 WHERE login='alice'"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.PasswordLogin("alice", code); !errors.Is(err, ErrTemporaryPasswordExpired) {
		t.Fatalf("expired code = %v", err)
	}
}

func TestPersonalPasswordPauseDoesNotExtendOrRevokeToken(t *testing.T) {
	db := testStateDB(t)
	legacy, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	result, err := db.ChangePassword("alice", legacy, testPersonalPassword)
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 5; i++ {
		_, err := db.PasswordLogin("alice", "wrong")
		if i < 5 && !errors.Is(err, ErrInvalidCredentials) {
			t.Fatalf("attempt %d: %v", i, err)
		}
		if i == 5 {
			var pause *PasswordRetryError
			if !errors.As(err, &pause) || pause.RetryAfter != 30*time.Second {
				t.Fatalf("fifth attempt = %v", err)
			}
		}
	}
	var before, after int64
	if err := db.read.QueryRow("SELECT password_retry_after FROM users WHERE login='alice'").Scan(&before); err != nil {
		t.Fatal(err)
	}
	_, err = db.PasswordLogin("alice", testPersonalPassword)
	var pause *PasswordRetryError
	if !errors.As(err, &pause) {
		t.Fatalf("pause bypass: %v", err)
	}
	if err := db.read.QueryRow("SELECT password_retry_after FROM users WHERE login='alice'").Scan(&after); err != nil {
		t.Fatal(err)
	}
	if before != after {
		t.Fatal("blocked request extends pause")
	}
	if _, ok, _ := db.Authenticate("alice", result.Token); !ok {
		t.Fatal("failed login revoked active device")
	}
	if _, err := db.sql.Exec("UPDATE users SET password_retry_after=unixepoch()-1 WHERE login='alice'"); err != nil {
		t.Fatal(err)
	}
	_, err = db.PasswordLogin("alice", "wrong")
	if !errors.As(err, &pause) || pause.RetryAfter != time.Minute {
		t.Fatalf("sixth attempt = %v", err)
	}
	if _, err := db.sql.Exec("UPDATE users SET password_retry_after=unixepoch()-1 WHERE login='alice'"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.PasswordLogin("alice", testPersonalPassword); err != nil {
		t.Fatal(err)
	}
	var failures int
	if err := db.read.QueryRow("SELECT password_failed_attempts FROM users WHERE login='alice'").Scan(&failures); err != nil || failures != 0 {
		t.Fatalf("success did not reset failures: %v", err)
	}
}

func TestPasswordPolicyAndTTL(t *testing.T) {
	db := testStateDB(t)
	if got, err := db.TemporaryPasswordTTL(); err != nil || got != 7*24*time.Hour {
		t.Fatalf("TTL = %v, %v", got, err)
	}
	if err := db.SetTemporaryPasswordTTL(2 * time.Hour); err != nil {
		t.Fatal(err)
	}
	code, err := db.AddUserWithPassword("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	var expiry int64
	if err := db.read.QueryRow("SELECT password_expires_at FROM users WHERE login='alice'").Scan(&expiry); err != nil {
		t.Fatal(err)
	}
	if delta := time.Until(time.Unix(expiry, 0)); delta < 119*time.Minute || delta > 121*time.Minute {
		t.Fatalf("expiry = %v", delta)
	}
	if _, err := db.ChangePassword("alice", code, "short"); !errors.Is(err, ErrInvalidPassword) {
		t.Fatalf("short password = %v", err)
	}
	password := "  очень длинный пароль  "
	if _, err := db.ChangePassword("alice", code, password); err != nil {
		t.Fatal(err)
	}
	if _, err := db.PasswordLogin("alice", strings.TrimSpace(password)); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("password was trimmed: %v", err)
	}
	if _, err := db.PasswordLogin("alice", password); err != nil {
		t.Fatal(err)
	}
	if err := db.SetTemporaryPasswordTTL(0); err == nil {
		t.Fatal("zero TTL accepted")
	}
}

func TestResetPasswordRevokesExistingCredentials(t *testing.T) {
	db := testStateDB(t)
	legacy, err := db.AddUser("alice", "Alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ClaimSession("alice", "phone"); err != nil {
		t.Fatal(err)
	}
	code, err := db.ResetPassword("alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := db.Authenticate("alice", legacy); ok {
		t.Fatal("reset kept old token")
	}
	if _, err := db.ChangePassword("alice", legacy, testPersonalPassword); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("old token bypassed reset: %v", err)
	}
	if _, err := db.ChangePassword("alice", code, testPersonalPassword); err != nil {
		t.Fatal(err)
	}
}
