package httpapi

import (
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const browserProtocol = "tinitalk.browser.v1"

type browserTicket struct {
	origin, authorization, deviceID, sessionID string
	expires                                    time.Time
}

type browserTickets struct {
	sync.Mutex
	items map[string]browserTicket
}

// Browser clients use explicit Authorization headers, never ambient cookies.
// Any HTTPS-hosted client can connect to a household without a central registry.
func (s *Server) browserCORS(w http.ResponseWriter, r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return false
	}
	if !s.validBrowserOrigin(origin) {
		http.Error(w, "unsupported origin", http.StatusForbidden)
		return true
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Expose-Headers", authReasonHeader+", "+signalProtocolHeader)
	if r.Method != http.MethodOptions {
		// A non-simple header prevents HTML forms from using ambient browser
		// Basic credentials. WebSocket authentication uses its own bound ticket.
		if strings.HasPrefix(r.URL.Path, "/api/") && r.URL.Path != "/api/browser/socket" && r.Header.Get(deviceIDHeader) == "" {
			http.Error(w, "explicit browser device header required", http.StatusForbidden)
			return true
		}
		return false
	}
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, "+sessionIDHeader+", "+deviceIDHeader)
	w.Header().Set("Access-Control-Max-Age", "600")
	w.WriteHeader(http.StatusNoContent)
	return true
}

func (s *Server) validBrowserOrigin(origin string) bool {
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return false
	}
	if u.Scheme == "https" {
		return true
	}
	return s.options.AllowInsecureLoopback && u.Scheme == "http" && (u.Hostname() == "localhost" || u.Hostname() == "127.0.0.1" || u.Hostname() == "::1")
}

func (s *Server) issueBrowserTicket(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	origin := r.Header.Get("Origin")
	if !s.validBrowserOrigin(origin) {
		http.Error(w, "origin required", http.StatusForbidden)
		return
	}
	session, managed := currentSession(r)
	if !managed || session.DeviceID != r.Header.Get(deviceIDHeader) {
		http.Error(w, "device session required", http.StatusUnauthorized)
		return
	}
	s.tickets.Lock()
	defer s.tickets.Unlock()
	now := time.Now()
	for key, ticket := range s.tickets.items {
		if !ticket.expires.After(now) {
			delete(s.tickets.items, key)
		}
	}
	if len(s.tickets.items) >= 4096 {
		http.Error(w, "ticket capacity exceeded", http.StatusTooManyRequests)
		return
	}
	var bytes [32]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		http.Error(w, "ticket unavailable", 500)
		return
	}
	key := base64.RawURLEncoding.EncodeToString(bytes[:])
	s.tickets.items[key] = browserTicket{origin, r.Header.Get("Authorization"), session.DeviceID, session.SessionID, now.Add(30 * time.Second)}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, map[string]any{"ticket": key, "foreground_call_notifications": true, "contact_changes": true})
}

func (s *Server) browserSocket(w http.ResponseWriter, r *http.Request) {
	var key string
	hasProtocol := false
	for _, protocol := range strings.Split(r.Header.Get("Sec-WebSocket-Protocol"), ",") {
		protocol = strings.TrimSpace(protocol)
		if protocol == browserProtocol {
			hasProtocol = true
		}
		if strings.HasPrefix(protocol, "ticket.") {
			key = strings.TrimPrefix(protocol, "ticket.")
		}
	}
	s.tickets.Lock()
	ticket, ok := s.tickets.items[key]
	if ok {
		delete(s.tickets.items, key)
	}
	s.tickets.Unlock()
	if !ok || !hasProtocol || !ticket.expires.After(time.Now()) || ticket.origin != r.Header.Get("Origin") {
		http.Error(w, "invalid socket ticket", http.StatusUnauthorized)
		return
	}
	r = r.Clone(r.Context())
	r.Header.Set("Authorization", ticket.authorization)
	r.Header.Set(deviceIDHeader, ticket.deviceID)
	r.Header.Set(sessionIDHeader, ticket.sessionID)
	r.Header.Set(signalProtocolHeader, signalProtocolVersion)
	r.Header.Set(signalAckHeader, signalAckVersion)
	w.Header().Set("Cache-Control", "no-store")
	// Recheck credentials and current session at consumption, including revocation.
	s.requireAuth(http.HandlerFunc(s.socket)).ServeHTTP(w, r)
}
