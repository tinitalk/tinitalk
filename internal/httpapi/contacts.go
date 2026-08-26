package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"

	"tinitalk/internal/state"
)

type contactResponse struct {
	Login              string  `json:"login"`
	DisplayName        string  `json:"display_name"`
	DefaultDisplayName string  `json:"default_display_name"`
	CustomName         *string `json:"custom_name"`
}

func (s *Server) contacts(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	contacts, err := s.db.ContactsForUser(currentUser(r).Login)
	if err != nil {
		http.Error(w, "contacts unavailable", http.StatusInternalServerError)
		return
	}
	out := make([]contactResponse, 0, len(contacts))
	for _, contact := range contacts {
		out = append(out, contactJSON(contact))
	}
	writeJSON(w, out)
}

func (s *Server) contactName(w http.ResponseWriter, r *http.Request) {
	var request struct {
		CustomName json.RawMessage `json:"custom_name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil || len(request.CustomName) == 0 {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	name := ""
	if !bytes.Equal(bytes.TrimSpace(request.CustomName), []byte("null")) {
		if err := json.Unmarshal(request.CustomName, &name); err != nil || strings.TrimSpace(name) == "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
	}
	owner := currentUser(r).Login
	login := r.PathValue("login")
	if err := s.db.SetContactName(owner, login, name); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	contact, err := s.db.ContactForUser(owner, login)
	if err != nil {
		http.Error(w, "contacts unavailable", http.StatusInternalServerError)
		return
	}
	writeJSON(w, contactJSON(contact))
}

func contactJSON(contact state.Contact) contactResponse {
	response := contactResponse{
		Login:              contact.Login,
		DisplayName:        contact.DisplayName,
		DefaultDisplayName: contact.DefaultDisplayName,
	}
	if contact.CustomName != "" {
		response.CustomName = &contact.CustomName
	}
	return response
}
