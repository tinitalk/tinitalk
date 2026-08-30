package httpapi

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"tinitalk/internal/auth"
	"tinitalk/internal/firebaseconfig"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

type Options struct {
	AllowInsecureLoopback bool
	FirebaseConfig        firebaseconfig.Config
	Hub                   *signaling.Hub
	SessionNotifier       SessionReplacementNotifier
}

type SessionReplacementNotifier interface {
	SessionReplaced(login, revokedSessionID string, devices []state.Device)
}

type Server struct {
	db             *state.DB
	auth           *auth.BasicAuthenticator
	hub            *signaling.Hub
	sessionNotify  SessionReplacementNotifier
	options        Options
	mux            *http.ServeMux
	socketTiming   socketTiming
	sessionClaimMu sync.Mutex
}

type socketTiming struct {
	writeTimeout time.Duration
	pongTimeout  time.Duration
	pingInterval time.Duration
}

var defaultSocketTiming = socketTiming{
	writeTimeout: 10 * time.Second,
	pongTimeout:  45 * time.Second,
	pingInterval: 20 * time.Second,
}

const apiVersion = 3

var serverCommit = "unknown"

func NewServer(db *state.DB, options Options) http.Handler {
	if options.Hub != nil {
		options.Hub.SetSessionStore(db)
	}
	s := &Server{
		db:            db,
		auth:          auth.NewBasicAuthenticator(db),
		hub:           options.Hub,
		sessionNotify: options.SessionNotifier,
		options:       options,
		mux:           http.NewServeMux(),
		socketTiming:  defaultSocketTiming,
	}
	s.routes()
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mux.ServeHTTP(w, r)
}

func (s *Server) routes() {
	s.mux.HandleFunc("/healthz", s.health)
	s.mux.Handle("/api/firebase-config", s.requireBasicAuth(http.HandlerFunc(s.firebaseConfig)))
	s.mux.Handle("/api/session", s.requireBasicAuth(http.HandlerFunc(s.session)))
	s.mux.Handle("/api/me", s.requireAuth(http.HandlerFunc(s.profile)))
	s.mux.Handle("/api/contacts", s.requireAuth(http.HandlerFunc(s.contacts)))
	s.mux.Handle("GET /api/contacts/page", s.requireAuth(http.HandlerFunc(s.contactsPage)))
	s.mux.Handle("PUT /api/contacts/{login}/name", s.requireAuth(http.HandlerFunc(s.contactName)))
	s.mux.Handle("/api/device", s.requireAuth(http.HandlerFunc(s.device)))
	s.mux.Handle("/api/calls", s.requireAuth(http.HandlerFunc(s.calls)))
	s.mux.Handle("/api/calls/read", s.requireAuth(http.HandlerFunc(s.readCalls)))
	s.mux.Handle("/api/socket", s.requireAuth(http.HandlerFunc(s.socket)))
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, struct {
		Service    string   `json:"service"`
		Status     string   `json:"status"`
		APIVersion int      `json:"api_version"`
		Commit     string   `json:"commit"`
		Features   []string `json:"features"`
	}{
		Service:    "tinitalk",
		Status:     "ok",
		APIVersion: apiVersion,
		Commit:     serverCommit,
		Features:   []string{"video_1to1", "single_device_session"},
	})
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}
