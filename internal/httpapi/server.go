package httpapi

import (
	"encoding/json"
	"net/http"

	"tinitalk/internal/auth"
	"tinitalk/internal/state"
)

type Options struct {
	AllowInsecureLoopback bool
}

type Server struct {
	db      *state.DB
	auth    *auth.BasicAuthenticator
	options Options
	mux     *http.ServeMux
}

func NewServer(db *state.DB, options Options) http.Handler {
	s := &Server{
		db:      db,
		auth:    auth.NewBasicAuthenticator(db),
		options: options,
		mux:     http.NewServeMux(),
	}
	s.routes()
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mux.ServeHTTP(w, r)
}

func (s *Server) routes() {
	s.mux.HandleFunc("/healthz", s.health)
	s.mux.Handle("/api/me", s.requireAuth(http.HandlerFunc(s.profile)))
	s.mux.Handle("/api/contacts", s.requireAuth(http.HandlerFunc(s.contacts)))
	s.mux.Handle("/api/device", s.requireAuth(http.HandlerFunc(s.device)))
	s.mux.Handle("/api/socket", s.requireAuth(http.HandlerFunc(s.socket)))
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}
