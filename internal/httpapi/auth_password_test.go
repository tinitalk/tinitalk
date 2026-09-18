package httpapi

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"tinitalk/internal/signaling"
)

const httpTestPersonalPassword = "correct horse battery staple"

func TestLegacyClientUsesExistingTokenOnPasswordCapableServer(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	handler := NewServer(db, Options{AllowInsecureLoopback: true, WebPushConfigID: "sha256:webpush", Hub: hub})
	server := httptest.NewServer(handler)
	defer server.Close()
	// The old client does not exchange passwords or call any /api/auth endpoint.
	session := claimHTTPSession(t, handler, "alice", tokens["alice"], "legacy-phone")
	for _, path := range []string{"/api/me", "/api/contacts/page?limit=20", "/api/calls?limit=20"} {
		response := requestWithSession(t, handler, http.MethodGet, path, nil, "alice", tokens["alice"], session)
		if response.Code != http.StatusOK {
			t.Fatalf("legacy %s = %d %s", path, response.Code, response.Body.String())
		}
	}
	socket := dialSessionSocket(t, server.URL, "alice", tokens["alice"], "legacy-phone", session)
	defer socket.Close()
	if _, valid, err := db.Authenticate("alice", tokens["alice"]); err != nil || !valid {
		t.Fatalf("legacy token invalidated: valid=%v err=%v", valid, err)
	}
	// Ordinary reconnect with the same device must not revoke the legacy session.
	if next := claimHTTPSession(t, handler, "alice", tokens["alice"], "legacy-phone"); next != session {
		t.Fatalf("legacy reconnect replaced session: %q -> %q", session, next)
	}
}

func TestEightCharacterPasswordBoundary(t *testing.T) {
	db, tokens := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})
	for _, password := range []string{"abcdefg", "абвгдеж"} {
		response := authRequest(t, handler, "/api/auth/password", map[string]string{
			"login": "alice", "password": tokens["alice"], "new_password": password,
		})
		if response.Code != http.StatusBadRequest {
			t.Fatalf("seven characters: %d", response.Code)
		}
	}
	response := authRequest(t, handler, "/api/auth/password", map[string]string{
		"login": "alice", "password": tokens["alice"], "new_password": "абвгдежз",
	})
	if response.Code != http.StatusOK {
		t.Fatalf("eight characters: %d %s", response.Code, response.Body.String())
	}
	response = authRequest(t, handler, "/api/auth/login", map[string]string{
		"login": "alice", "password": "абвгдежз",
	})
	if response.Code != http.StatusOK {
		t.Fatalf("login: %d", response.Code)
	}
}

func TestPersonalPasswordLoginRotatesAccessAndCanClaimBrowserSession(t *testing.T) {
	db, tokens := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})
	setup := authRequest(t, handler, "/api/auth/password", map[string]string{
		"login": "alice", "password": tokens["alice"], "new_password": httpTestPersonalPassword,
	})
	var first struct {
		Token string `json:"token"`
	}
	if setup.Code != http.StatusOK || json.Unmarshal(setup.Body.Bytes(), &first) != nil || first.Token == "" {
		t.Fatalf("setup failed: %d", setup.Code)
	}
	login := authRequest(t, handler, "/api/auth/login", map[string]string{
		"login": "alice", "password": httpTestPersonalPassword,
	})
	var next struct {
		Token string `json:"token"`
	}
	if login.Code != http.StatusOK || json.Unmarshal(login.Body.Bytes(), &next) != nil || next.Token == "" || next.Token == first.Token {
		t.Fatalf("login did not rotate token: %d", login.Code)
	}
	for _, old := range []string{tokens["alice"], first.Token} {
		if _, valid, err := db.Authenticate("alice", old); err != nil || valid {
			t.Fatalf("old credential still usable: valid=%v err=%v", valid, err)
		}
	}
	claimed := request(t, handler, http.MethodPost, "/api/browser/session", []byte(`{"device_id":"browser"}`), "alice", next.Token)
	if claimed.Code != http.StatusOK {
		t.Fatalf("claim: %d %s", claimed.Code, claimed.Body.String())
	}
	var session struct {
		ID string `json:"session_id"`
	}
	if err := json.Unmarshal(claimed.Body.Bytes(), &session); err != nil || session.ID == "" {
		t.Fatalf("missing claimed session: %v", err)
	}
	loggedOut := requestWithSession(t, handler, http.MethodPost, "/api/auth/logout", nil, "alice", next.Token, session.ID)
	if loggedOut.Code != http.StatusNoContent {
		t.Fatalf("logout: %d", loggedOut.Code)
	}
	if _, valid, err := db.Authenticate("alice", next.Token); err != nil || valid {
		t.Fatalf("logout did not revoke token: valid=%v err=%v", valid, err)
	}
	// The personal password survives an explicit logout.
	if again := authRequest(t, handler, "/api/auth/login", map[string]string{"login": "alice", "password": httpTestPersonalPassword}); again.Code != http.StatusOK {
		t.Fatalf("login after logout: %d", again.Code)
	}
}

func TestPasswordLoginAcceptsExistingAccessTokenWithoutRotation(t *testing.T) {
	db, tokens := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})

	response := request(t, handler, http.MethodPost, "/api/auth/login", []byte(`{"login":"alice","password":"`+tokens["alice"]+`"}`), "", "")
	if response.Code != http.StatusOK {
		t.Fatalf("login status = %d, body %s", response.Code, response.Body.String())
	}
	var result struct {
		Token            string `json:"token"`
		PasswordRequired bool   `json:"password_required"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Token != tokens["alice"] || result.PasswordRequired {
		t.Fatalf("login result = %+v, want unchanged access token", result)
	}
	if _, ok, err := db.Authenticate("alice", tokens["alice"]); err != nil || !ok {
		t.Fatalf("existing token was rotated: ok=%v err=%v", ok, err)
	}
}

func TestProfileReportsWhetherPersonalPasswordIsSet(t *testing.T) {
	db, tokens := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})

	legacy := request(t, handler, http.MethodGet, "/api/me", nil, "alice", tokens["alice"])
	var legacyProfile map[string]any
	if err := json.Unmarshal(legacy.Body.Bytes(), &legacyProfile); err != nil {
		t.Fatal(err)
	}
	if passwordSet, exists := legacyProfile["password_set"]; !exists || passwordSet != false {
		t.Fatalf("legacy profile password_set = %#v, exists=%v", passwordSet, exists)
	}

	changed := authRequest(t, handler, "/api/auth/password", map[string]string{
		"login": "alice", "password": tokens["alice"], "new_password": httpTestPersonalPassword,
	})
	if changed.Code != http.StatusOK {
		t.Fatalf("password setup status = %d body %s", changed.Code, changed.Body.String())
	}
	var result struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(changed.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	claimed := request(t, handler, http.MethodPost, "/api/browser/session", []byte(`{"device_id":"browser"}`), "alice", result.Token)
	if claimed.Code != http.StatusOK {
		t.Fatalf("session claim status = %d body %s", claimed.Code, claimed.Body.String())
	}
	var session struct {
		SessionID string `json:"session_id"`
	}
	if err := json.Unmarshal(claimed.Body.Bytes(), &session); err != nil {
		t.Fatal(err)
	}
	personal := requestWithSession(t, handler, http.MethodGet, "/api/me", nil, "alice", result.Token, session.SessionID)
	var personalProfile map[string]any
	if err := json.Unmarshal(personal.Body.Bytes(), &personalProfile); err != nil {
		t.Fatal(err)
	}
	if personalProfile["password_set"] != true {
		t.Fatalf("personal profile = %v", personalProfile)
	}
}

func TestTemporaryPasswordRequiresSetupBeforeGrantingAccess(t *testing.T) {
	db, _ := testDB(t)
	code, err := db.ResetPassword("alice")
	if err != nil {
		t.Fatal(err)
	}
	handler := NewServer(db, Options{AllowInsecureLoopback: true})

	checked := authRequest(t, handler, "/api/auth/login", map[string]string{"login": "alice", "password": code})
	if checked.Code != http.StatusOK {
		t.Fatalf("temporary login status = %d, body %s", checked.Code, checked.Body.String())
	}
	var setupResult map[string]any
	if err := json.Unmarshal(checked.Body.Bytes(), &setupResult); err != nil {
		t.Fatal(err)
	}
	if setupResult["password_required"] != true || setupResult["token"] != nil {
		t.Fatalf("temporary login response = %v", setupResult)
	}
	if _, ok, err := db.Authenticate("alice", code); err != nil || ok {
		t.Fatalf("temporary password granted API access: ok=%v err=%v", ok, err)
	}

	changed := authRequest(t, handler, "/api/auth/password", map[string]string{
		"login": "alice", "password": code, "new_password": httpTestPersonalPassword,
	})
	if changed.Code != http.StatusOK {
		t.Fatalf("change password status = %d, body %s", changed.Code, changed.Body.String())
	}
	var result struct {
		Token            string `json:"token"`
		PasswordRequired bool   `json:"password_required"`
	}
	if err := json.Unmarshal(changed.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Token) != 43 || result.PasswordRequired {
		t.Fatalf("change password response = %+v", result)
	}
	if _, ok, err := db.Authenticate("alice", result.Token); err != nil || !ok {
		t.Fatalf("returned token invalid: ok=%v err=%v", ok, err)
	}
}

func TestPasswordRoutesReturnBoundedGenericErrors(t *testing.T) {
	db, _ := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})

	tests := []struct {
		name string
		body string
	}{
		{"unknown field", `{"login":"alice","password":"wrong","extra":true}`},
		{"trailing JSON", `{"login":"alice","password":"wrong"}{}`},
		{"empty login", `{"login":"","password":"wrong"}`},
		{"long login", `{"login":"` + strings.Repeat("a", maxAuthLoginRunes+1) + `","password":"wrong"}`},
		{"long password", `{"login":"alice","password":"` + strings.Repeat("a", maxAuthCredentialRunes+1) + `"}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(test.body))
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			if response.Code != http.StatusBadRequest || response.Header().Get("Content-Type") != "application/json" {
				t.Fatalf("status = %d, content type = %q, body %s", response.Code, response.Header().Get("Content-Type"), response.Body.String())
			}
			var failure authErrorResponse
			if err := json.Unmarshal(response.Body.Bytes(), &failure); err != nil || failure.Error != "invalid_password" {
				t.Fatalf("failure = %+v, err %v", failure, err)
			}
		})
	}

	short := authRequest(t, handler, "/api/auth/password", map[string]string{
		"login": "alice", "password": "wrong", "new_password": "short",
	})
	if short.Code != http.StatusBadRequest || !bytes.Contains(short.Body.Bytes(), []byte(`"error":"invalid_password"`)) {
		t.Fatalf("short password response = %d %s", short.Code, short.Body.String())
	}
}

func TestPasswordRoutesMapLockPauseAndExpiryErrors(t *testing.T) {
	t.Run("temporary lock", func(t *testing.T) {
		db, _ := testDB(t)
		if _, err := db.ResetPassword("alice"); err != nil {
			t.Fatal(err)
		}
		handler := NewServer(db, Options{AllowInsecureLoopback: true})
		for attempt := 1; attempt <= 5; attempt++ {
			response := authRequest(t, handler, "/api/auth/login", map[string]string{"login": "alice", "password": "wrong"})
			wantStatus, wantCode := http.StatusUnauthorized, "invalid_credentials"
			if attempt == 5 {
				wantStatus, wantCode = http.StatusForbidden, "temporary_password_locked"
			}
			assertAuthFailure(t, response, wantStatus, wantCode)
		}
	})

	t.Run("personal pause", func(t *testing.T) {
		db, tokens := testDB(t)
		handler := NewServer(db, Options{AllowInsecureLoopback: true})
		changed := authRequest(t, handler, "/api/auth/password", map[string]string{
			"login": "alice", "password": tokens["alice"], "new_password": httpTestPersonalPassword,
		})
		if changed.Code != http.StatusOK {
			t.Fatalf("setup status = %d body %s", changed.Code, changed.Body.String())
		}
		for attempt := 1; attempt <= 5; attempt++ {
			response := authRequest(t, handler, "/api/auth/login", map[string]string{"login": "alice", "password": "wrong"})
			if attempt < 5 {
				assertAuthFailure(t, response, http.StatusUnauthorized, "invalid_credentials")
				continue
			}
			assertAuthFailure(t, response, http.StatusTooManyRequests, "password_retry_later")
			var failure authErrorResponse
			if err := json.Unmarshal(response.Body.Bytes(), &failure); err != nil {
				t.Fatal(err)
			}
			if failure.RetryAfter != 30 || response.Header().Get("Retry-After") != "30" {
				t.Fatalf("retry response = %+v headers=%v", failure, response.Header())
			}
		}
	})

	t.Run("temporary expiry", func(t *testing.T) {
		db, _ := testDB(t)
		if err := db.SetTemporaryPasswordTTL(time.Second); err != nil {
			t.Fatal(err)
		}
		code, err := db.ResetPassword("alice")
		if err != nil {
			t.Fatal(err)
		}
		handler := NewServer(db, Options{AllowInsecureLoopback: true})
		time.Sleep(1100 * time.Millisecond)
		response := authRequest(t, handler, "/api/auth/login", map[string]string{"login": "alice", "password": code})
		assertAuthFailure(t, response, http.StatusForbidden, "temporary_password_expired")
	})
}

func TestPasswordAttemptLimiterUsesPeerAddressAndIsBounded(t *testing.T) {
	db, _ := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true}).(*Server)
	server.authAttempts = newIPAttemptLimiter(2, time.Minute, 2)

	for attempt := 1; attempt <= 3; attempt++ {
		request := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"login":"missing","password":"wrong"}`))
		request.RemoteAddr = "192.0.2.10:1234"
		request.Header.Set("X-Forwarded-For", "198.51.100."+string(rune('0'+attempt)))
		response := httptest.NewRecorder()
		server.ServeHTTP(response, request)
		if attempt < 3 && response.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d status = %d, body %s", attempt, response.Code, response.Body.String())
		}
		if attempt == 3 {
			if response.Code != http.StatusTooManyRequests || response.Header().Get("Retry-After") == "" {
				t.Fatalf("limited response = %d headers=%v body=%s", response.Code, response.Header(), response.Body.String())
			}
		}
	}

	for _, address := range []string{"192.0.2.11:1", "192.0.2.12:1", "192.0.2.13:1"} {
		server.authAttempts.allow(address)
	}
	if len(server.authAttempts.entries) != 2 {
		t.Fatalf("limiter entries = %d, want bounded at 2", len(server.authAttempts.entries))
	}
}

func TestPasswordMutationAdmissionDoesNotQueueBehindSessionClaims(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{AllowInsecureLoopback: true}).(*Server)
	server.sessionClaimMu.Lock()

	responses := make(chan *httptest.ResponseRecorder, 3)
	body, err := json.Marshal(map[string]string{"login": "alice", "password": tokens["alice"]})
	if err != nil {
		t.Fatal(err)
	}
	for range 3 {
		go func() {
			request := httptest.NewRequest(http.MethodPost, "/api/auth/login", bytes.NewReader(body))
			response := httptest.NewRecorder()
			server.ServeHTTP(response, request)
			responses <- response
		}()
	}
	deadline := time.After(500 * time.Millisecond)
	for range 2 {
		select {
		case response := <-responses:
			if response.Code != http.StatusServiceUnavailable || !bytes.Contains(response.Body.Bytes(), []byte(`"error":"auth_busy"`)) {
				t.Fatalf("overflow response = %d %s", response.Code, response.Body.String())
			}
		case <-deadline:
			server.sessionClaimMu.Unlock()
			t.Fatal("password attempts queued behind the session mutation lock")
		}
	}
	server.sessionClaimMu.Unlock()
	select {
	case response := <-responses:
		if response.Code != http.StatusOK {
			t.Fatalf("admitted response = %d %s", response.Code, response.Body.String())
		}
	case <-time.After(2 * time.Second):
		t.Fatal("admitted password request did not finish after claim lock release")
	}
}

func TestPasswordRoutesAllowCrossOriginWithoutDeviceHeader(t *testing.T) {
	db, _ := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})
	request := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"login":"missing","password":"wrong"}`))
	request.Header.Set("Origin", "https://app.example")
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized || response.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Fatalf("cross-origin login = %d headers=%v body=%s", response.Code, response.Header(), response.Body.String())
	}
}

func TestPasswordRoutesOnlyAllowPost(t *testing.T) {
	db, _ := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})
	for _, path := range []string{"/api/auth/login", "/api/auth/password", "/api/auth/logout"} {
		for _, method := range []string{http.MethodGet, http.MethodPut, http.MethodDelete} {
			request := httptest.NewRequest(method, path, nil)
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			if response.Code != http.StatusMethodNotAllowed || response.Header().Get("Allow") != http.MethodPost {
				t.Fatalf("%s %s = %d Allow %q, want 405 Allow POST", method, path, response.Code, response.Header().Get("Allow"))
			}
		}
	}
}

func TestPasswordLogoutRevokesTokenAndClosesCurrentSocket(t *testing.T) {
	db, tokens := testDB(t)
	credentials, err := db.ChangePassword("alice", tokens["alice"], httpTestPersonalPassword)
	if err != nil {
		t.Fatal(err)
	}
	tokens["alice"] = credentials.Token
	claim, err := db.ClaimSession("alice", "phone")
	if err != nil {
		t.Fatal(err)
	}
	hub := signaling.NewHub(signaling.NoopNotifier{})
	server := httptest.NewServer(NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}))
	defer server.Close()
	socket := dialSessionSocket(t, server.URL, "alice", tokens["alice"], "phone", claim.Current.SessionID)
	defer socket.Close()

	request, err := http.NewRequest(http.MethodPost, server.URL+"/api/auth/logout", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.SetBasicAuth("alice", tokens["alice"])
	request.Header.Set(sessionIDHeader, claim.Current.SessionID)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("logout status = %d", response.StatusCode)
	}
	if _, ok, err := db.Authenticate("alice", tokens["alice"]); err != nil || ok {
		t.Fatalf("logged out token accepted: ok=%v err=%v", ok, err)
	}
	if err := socket.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := socket.ReadMessage(); err == nil {
		t.Fatal("socket remained open after logout")
	}
}

func TestLegacyLogoutClosesSessionButAllowsLoginWithSameToken(t *testing.T) {
	db, tokens := testDB(t)
	hub := signaling.NewHub(signaling.NoopNotifier{})
	handler := NewServer(db, Options{AllowInsecureLoopback: true, WebPushConfigID: "sha256:webpush", Hub: hub})
	server := httptest.NewServer(handler)
	defer server.Close()
	session := claimHTTPSession(t, handler, "alice", tokens["alice"], "phone")
	socket := dialSessionSocket(t, server.URL, "alice", tokens["alice"], "phone", session)
	defer socket.Close()
	response := requestWithSession(t, handler, http.MethodPost, "/api/auth/logout", nil, "alice", tokens["alice"], session)
	if response.Code != http.StatusNoContent {
		t.Fatalf("logout: %d %s", response.Code, response.Body.String())
	}
	if devices, err := db.PushTargetsForUser("alice"); err != nil || len(devices) != 0 {
		t.Fatalf("logout kept push targets: %v, %v", devices, err)
	}
	if err := socket.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := socket.ReadMessage(); err == nil {
		t.Fatal("socket remained open after logout")
	}
	response = requestWithSession(t, handler, http.MethodGet, "/api/me", nil, "alice", tokens["alice"], session)
	if response.Code != http.StatusUnauthorized || response.Header().Get("X-TiniTalk-Auth-Reason") != "session_replaced" {
		t.Fatalf("old session still usable: %d %s", response.Code, response.Body.String())
	}
	response = authRequest(t, handler, "/api/auth/login", map[string]string{"login": "alice", "password": tokens["alice"]})
	var login struct {
		Token            string `json:"token"`
		PasswordRequired bool   `json:"password_required"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &login) != nil ||
		login.Token != tokens["alice"] || login.PasswordRequired {
		t.Fatalf("legacy login after logout: %d %s", response.Code, response.Body.String())
	}
	next := claimHTTPSession(t, handler, "alice", login.Token, "phone")
	if next == session {
		t.Fatal("reused logged-out session")
	}
	response = requestWithSession(t, handler, http.MethodGet, "/api/me", nil, "alice", login.Token, next)
	if response.Code != http.StatusOK {
		t.Fatalf("new session: %d %s", response.Code, response.Body.String())
	}
}

func TestLogoutCannotEndAnotherDevicesSession(t *testing.T) {
	for _, mode := range []string{"legacy_token", "password_token"} {
		t.Run(mode, func(t *testing.T) {
			db, tokens := testDB(t)
			token := tokens["alice"]
			if mode == "password_token" {
				credentials, err := db.ChangePassword("alice", token, httpTestPersonalPassword)
				if err != nil {
					t.Fatal(err)
				}
				token = credentials.Token
			}
			handler := NewServer(db, Options{AllowInsecureLoopback: true, WebPushConfigID: "sha256:webpush"})
			oldSession := claimHTTPSession(t, handler, "alice", token, "old-phone")
			// Importing the same token can replace a session without rotating the token.
			currentSession := claimHTTPSession(t, handler, "alice", token, "new-phone")
			for _, session := range []string{oldSession, "", "unknown-session"} {
				response := requestWithSession(t, handler, http.MethodPost, "/api/auth/logout", nil, "alice", token, session)
				assertSessionReplacedResponse(t, "logout", response)
				if response.Header().Get("Content-Type") != "application/json" {
					t.Fatalf("logout failure is not JSON: %s", response.Body.String())
				}
				response = requestWithSession(t, handler, http.MethodGet, "/api/me", nil, "alice", token, currentSession)
				if response.Code != http.StatusOK {
					t.Fatalf("rejected logout invalidated current access: %d", response.Code)
				}
				devices, err := db.PushTargetsForUser("alice")
				if err != nil || len(devices) != 1 || devices[0].DeviceID != "new-phone" {
					t.Fatalf("rejected logout changed push targets: %v, %v", devices, err)
				}
			}
			response := requestWithSession(t, handler, http.MethodPost, "/api/auth/logout", nil, "alice", token, currentSession)
			if response.Code != http.StatusNoContent {
				t.Fatalf("current session cannot log out: %d", response.Code)
			}
		})
	}
}

func TestPasswordLogoutAuthenticationErrorsAreJSON(t *testing.T) {
	db, _ := testDB(t)
	handler := NewServer(db, Options{AllowInsecureLoopback: true})
	request := httptest.NewRequest(http.MethodPost, "/api/auth/logout", nil)
	request.SetBasicAuth("alice", "invalid")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized || response.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("logout failure = %d headers=%v body=%s", response.Code, response.Header(), response.Body.String())
	}
	var failure authErrorResponse
	if err := json.Unmarshal(response.Body.Bytes(), &failure); err != nil || failure.Error != "invalid_credentials" {
		t.Fatalf("failure = %+v, err %v", failure, err)
	}
}

func TestSocketHeartbeatClosesAfterOutOfProcessPasswordReset(t *testing.T) {
	db, tokens := testDB(t)
	claim, err := db.ClaimSession("alice", "phone")
	if err != nil {
		t.Fatal(err)
	}
	hub := signaling.NewHub(signaling.NoopNotifier{})
	handler := NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}).(*Server)
	handler.socketTiming = socketTiming{writeTimeout: 100 * time.Millisecond, pongTimeout: time.Second, pingInterval: 20 * time.Millisecond}
	server := httptest.NewServer(handler)
	defer server.Close()
	socket := dialSessionSocket(t, server.URL, "alice", tokens["alice"], "phone", claim.Current.SessionID)
	defer socket.Close()

	closed := make(chan error, 1)
	go func() {
		for {
			if _, _, err := socket.ReadMessage(); err != nil {
				closed <- err
				return
			}
		}
	}()
	if _, err := db.ResetPassword("alice"); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-closed:
		if err == nil {
			t.Fatal("socket reader ended without an error")
		}
	case <-time.After(750 * time.Millisecond):
		t.Fatal("idle socket remained open after external password reset")
	}
}

func TestSocketRejectsInboundDataAfterOutOfProcessPasswordReset(t *testing.T) {
	db, tokens := testDB(t)
	claim, err := db.ClaimSession("alice", "phone")
	if err != nil {
		t.Fatal(err)
	}
	hub := signaling.NewHub(signaling.NoopNotifier{})
	handler := NewServer(db, Options{AllowInsecureLoopback: true, Hub: hub}).(*Server)
	handler.socketTiming = socketTiming{writeTimeout: 100 * time.Millisecond, pongTimeout: time.Hour, pingInterval: time.Hour}
	server := httptest.NewServer(handler)
	defer server.Close()
	socket := dialSessionSocket(t, server.URL, "alice", tokens["alice"], "phone", claim.Current.SessionID)
	defer socket.Close()

	if _, err := db.ResetPassword("alice"); err != nil {
		t.Fatal(err)
	}
	if err := socket.WriteMessage(websocket.TextMessage, []byte(`{`)); err != nil {
		t.Fatal(err)
	}
	if err := socket.SetReadDeadline(time.Now().Add(750 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := socket.ReadMessage(); err == nil {
		t.Fatal("revoked socket received a protocol response instead of closing")
	}
}

func authRequest(t *testing.T, handler http.Handler, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(encoded))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func assertAuthFailure(t *testing.T, response *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	if response.Code != status {
		t.Fatalf("status = %d, want %d; body %s", response.Code, status, response.Body.String())
	}
	var failure authErrorResponse
	if err := json.Unmarshal(response.Body.Bytes(), &failure); err != nil {
		t.Fatal(err)
	}
	if failure.Error != code {
		t.Fatalf("error = %q, want %q", failure.Error, code)
	}
}

func dialSessionSocket(t *testing.T, baseURL, login, token, deviceID, sessionID string) *websocket.Conn {
	t.Helper()
	header := http.Header{}
	header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(login+":"+token)))
	header.Set(signalProtocolHeader, signalProtocolVersion)
	header.Set(deviceIDHeader, deviceID)
	header.Set(sessionIDHeader, sessionID)
	url := "ws" + baseURL[len("http"):] + "/api/socket"
	connection, response, err := websocket.DefaultDialer.Dial(url, header)
	if response != nil && response.Body != nil {
		defer response.Body.Close()
	}
	if err != nil {
		t.Fatal(err)
	}
	return connection
}
