package httpapi

import (
	"context"
	"net/http"

	"tinitalk/internal/state"
)

type userContextKey struct{}
type sessionContextKey struct{}

func (s *Server) requireAuth(next http.Handler) http.Handler {
	return s.requireBasicAuth(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session, managed, err := s.db.CurrentSession(currentUser(r).Login)
		if err != nil {
			http.Error(w, "authentication unavailable", http.StatusInternalServerError)
			return
		}
		if managed && r.Header.Get(sessionIDHeader) != session.SessionID {
			writeSessionReplaced(w)
			return
		}
		if managed {
			ctx := context.WithValue(r.Context(), sessionContextKey{}, session)
			r = r.WithContext(ctx)
		}
		next.ServeHTTP(w, r)
	}))
}

func (s *Server) requireBasicAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil && !s.options.AllowInsecureLoopback {
			w.Header().Set("WWW-Authenticate", `Basic realm="tinitalk"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		login, token, ok := r.BasicAuth()
		if !ok {
			w.Header().Set("WWW-Authenticate", `Basic realm="tinitalk"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		user, ok := s.auth.Authenticate(login, token)
		if !ok {
			w.Header().Set("WWW-Authenticate", `Basic realm="tinitalk"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), userContextKey{}, user)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func writeSessionReplaced(w http.ResponseWriter) {
	w.Header().Set(authReasonHeader, authReasonSessionReplaced)
	http.Error(w, "unauthorized", http.StatusUnauthorized)
}

func currentUser(r *http.Request) state.User {
	user, _ := r.Context().Value(userContextKey{}).(state.User)
	return user
}

func currentSession(r *http.Request) (state.AccountSession, bool) {
	session, ok := r.Context().Value(sessionContextKey{}).(state.AccountSession)
	return session, ok
}
