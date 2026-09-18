package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
	"unicode/utf8"

	"tinitalk/internal/state"
)

const (
	maxAuthLoginRunes      = 128
	maxAuthCredentialRunes = 128
	minPersonalPassword    = 8
	defaultIPAttemptLimit  = 12
	defaultIPAttemptWindow = time.Minute
	maxIPAttemptEntries    = 1024
)

type passwordLoginRequest struct {
	Login    string `json:"login"`
	Password string `json:"password"`
}

type changePasswordRequest struct {
	Login       string `json:"login"`
	Password    string `json:"password"`
	NewPassword string `json:"new_password"`
}

type authErrorResponse struct {
	Error      string `json:"error"`
	RetryAfter int64  `json:"retry_after,omitempty"`
}

func (s *Server) passwordLogin(w http.ResponseWriter, r *http.Request) {
	if !s.passwordTransportAllowed(w, r) {
		return
	}
	var request passwordLoginRequest
	if !decodeStrictJSON(r, &request) || !validCredentialFields(request.Login, request.Password) {
		writeAuthError(w, http.StatusBadRequest, "invalid_password", 0)
		return
	}
	if allowed, retryAfter := s.authAttempts.allow(r.RemoteAddr); !allowed {
		writeAuthError(w, http.StatusTooManyRequests, "password_retry_later", retryAfter)
		return
	}
	if !s.beginPasswordMutation() {
		writePasswordStateError(w, state.ErrAuthBusy)
		return
	}
	defer s.endPasswordMutation()

	s.sessionClaimMu.Lock()
	result, err := s.db.PasswordLogin(request.Login, request.Password)
	if err == nil && result.Rotated {
		s.publishCurrentSessionLocked(request.Login)
	}
	s.sessionClaimMu.Unlock()
	if err != nil {
		writePasswordStateError(w, err)
		return
	}
	writeJSON(w, result)
}

func (s *Server) changePassword(w http.ResponseWriter, r *http.Request) {
	if !s.passwordTransportAllowed(w, r) {
		return
	}
	var request changePasswordRequest
	if !decodeStrictJSON(r, &request) || !validCredentialFields(request.Login, request.Password) ||
		!validPersonalPassword(request.NewPassword) {
		writeAuthError(w, http.StatusBadRequest, "invalid_password", 0)
		return
	}
	if allowed, retryAfter := s.authAttempts.allow(r.RemoteAddr); !allowed {
		writeAuthError(w, http.StatusTooManyRequests, "password_retry_later", retryAfter)
		return
	}
	if !s.beginPasswordMutation() {
		writePasswordStateError(w, state.ErrAuthBusy)
		return
	}
	defer s.endPasswordMutation()

	s.sessionClaimMu.Lock()
	result, err := s.db.ChangePassword(request.Login, request.Password, request.NewPassword)
	if err == nil {
		s.publishCurrentSessionLocked(request.Login)
	}
	s.sessionClaimMu.Unlock()
	if err != nil {
		writePasswordStateError(w, err)
		return
	}
	writeJSON(w, result)
}

func (s *Server) passwordLogout(w http.ResponseWriter, r *http.Request) {
	login, token, ok := r.BasicAuth()
	if !ok || login != currentUser(r).Login {
		writeAuthUnauthorized(w)
		return
	}
	if !s.beginPasswordMutation() {
		writePasswordStateError(w, state.ErrAuthBusy)
		return
	}
	defer s.endPasswordMutation()
	s.sessionClaimMu.Lock()
	_, valid, err := s.auth.Authenticate(login, token)
	if err == nil && !valid {
		err = state.ErrInvalidCredentials
	}
	if err == nil {
		err = s.db.Logout(login, token, r.Header.Get(sessionIDHeader))
	}
	if err == nil {
		s.publishCurrentSessionLocked(login)
	}
	s.sessionClaimMu.Unlock()
	if err != nil {
		writePasswordStateError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) requirePasswordBasicAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.passwordTransportAllowed(w, r) {
			return
		}
		login, token, ok := r.BasicAuth()
		if !ok {
			writeAuthUnauthorized(w)
			return
		}
		user, valid, err := s.auth.Authenticate(login, token)
		if err != nil {
			writePasswordStateError(w, err)
			return
		}
		if !valid {
			writeAuthUnauthorized(w)
			return
		}
		ctx := context.WithValue(r.Context(), userContextKey{}, user)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *Server) beginPasswordMutation() bool {
	select {
	case s.authMutations <- struct{}{}:
		return true
	default:
		return false
	}
}

func (s *Server) endPasswordMutation() {
	<-s.authMutations
}

func (s *Server) passwordTransportAllowed(w http.ResponseWriter, r *http.Request) bool {
	if r.TLS != nil || s.options.AllowInsecureLoopback {
		return true
	}
	writeAuthUnauthorized(w)
	return false
}

func (s *Server) publishCurrentSessionLocked(login string) {
	session, managed, err := s.db.CurrentSession(login)
	if err != nil || !managed || session.SessionID == "" {
		return
	}
	if s.hub != nil {
		s.hub.ReplaceSession(login, session.SessionID)
	}
}

func decodeStrictJSON(r *http.Request, target any) bool {
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return false
	}
	return decoder.Decode(&struct{}{}) == io.EOF
}

func validCredentialFields(login, password string) bool {
	return validBoundedString(login, 1, maxAuthLoginRunes) &&
		validBoundedString(password, 1, maxAuthCredentialRunes)
}

func validPersonalPassword(password string) bool {
	return validBoundedString(password, minPersonalPassword, maxAuthCredentialRunes)
}

func validBoundedString(value string, minimum, maximum int) bool {
	if !utf8.ValidString(value) {
		return false
	}
	length := utf8.RuneCountInString(value)
	return length >= minimum && length <= maximum
}

func writePasswordStateError(w http.ResponseWriter, err error) {
	var retry *state.PasswordRetryError
	switch {
	case errors.Is(err, state.ErrSessionReplaced):
		w.Header().Set(authReasonHeader, authReasonSessionReplaced)
		writeAuthError(w, http.StatusUnauthorized, "invalid_credentials", 0)
	case errors.As(err, &retry):
		writeAuthError(w, http.StatusTooManyRequests, "password_retry_later", retry.RetryAfter)
	case errors.Is(err, state.ErrInvalidCredentials):
		writeAuthError(w, http.StatusUnauthorized, "invalid_credentials", 0)
	case errors.Is(err, state.ErrTemporaryPasswordExpired):
		writeAuthError(w, http.StatusForbidden, "temporary_password_expired", 0)
	case errors.Is(err, state.ErrTemporaryPasswordLocked):
		writeAuthError(w, http.StatusForbidden, "temporary_password_locked", 0)
	case errors.Is(err, state.ErrInvalidPassword):
		writeAuthError(w, http.StatusBadRequest, "invalid_password", 0)
	default:
		writeAuthError(w, http.StatusServiceUnavailable, "auth_busy", 0)
	}
}

func writeAuthUnauthorized(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", `Basic realm="tinitalk"`)
	writeAuthError(w, http.StatusUnauthorized, "invalid_credentials", 0)
}

func writeAuthError(w http.ResponseWriter, status int, code string, retryAfter time.Duration) {
	response := authErrorResponse{Error: code}
	if retryAfter > 0 {
		response.RetryAfter = max(1, (retryAfter.Nanoseconds()+int64(time.Second)-1)/int64(time.Second))
		w.Header().Set("Retry-After", strconv.FormatInt(response.RetryAfter, 10))
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(response)
}

type ipAttemptLimiter struct {
	mu         sync.Mutex
	entries    map[string]ipAttemptEntry
	limit      int
	window     time.Duration
	maxEntries int
	now        func() time.Time
}

type ipAttemptEntry struct {
	started  time.Time
	lastSeen time.Time
	count    int
}

func newIPAttemptLimiter(limit int, window time.Duration, maxEntries int) *ipAttemptLimiter {
	return &ipAttemptLimiter{
		entries:    make(map[string]ipAttemptEntry),
		limit:      limit,
		window:     window,
		maxEntries: maxEntries,
		now:        time.Now,
	}
}

func (l *ipAttemptLimiter) allow(remoteAddress string) (bool, time.Duration) {
	key := remoteIP(remoteAddress)
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()

	entry, exists := l.entries[key]
	if exists && now.Sub(entry.started) >= l.window {
		entry = ipAttemptEntry{}
		exists = false
	}
	if !exists {
		if len(l.entries) >= l.maxEntries {
			l.evictOldest()
		}
		entry.started = now
	}
	entry.lastSeen = now
	if entry.count >= l.limit {
		l.entries[key] = entry
		return false, max(time.Second, l.window-now.Sub(entry.started))
	}
	entry.count++
	l.entries[key] = entry
	return true, 0
}

func (l *ipAttemptLimiter) evictOldest() {
	oldestKey := ""
	var oldestTime time.Time
	for key, entry := range l.entries {
		if oldestKey == "" || entry.lastSeen.Before(oldestTime) {
			oldestKey = key
			oldestTime = entry.lastSeen
		}
	}
	if oldestKey != "" {
		delete(l.entries, oldestKey)
	}
}

func remoteIP(remoteAddress string) string {
	host, _, err := net.SplitHostPort(remoteAddress)
	if err != nil {
		return "unknown"
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return "unknown"
	}
	return ip.String()
}
