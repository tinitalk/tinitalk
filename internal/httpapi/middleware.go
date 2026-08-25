package httpapi

import (
	"context"
	"net/http"

	"tinitalk/internal/state"
)

type userContextKey struct{}

func (s *Server) requireAuth(next http.Handler) http.Handler {
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

func currentUser(r *http.Request) state.User {
	user, _ := r.Context().Value(userContextKey{}).(state.User)
	return user
}
