package httpapi

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"

	"tinitalk/internal/state"
)

type contactResponse struct {
	Login       string  `json:"login"`
	DisplayName string  `json:"display_name"`
	CustomName  *string `json:"custom_name"`
	CanCall     bool    `json:"can_call"`
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

func (s *Server) contactsPage(w http.ResponseWriter, r *http.Request) {
	limit, err := queryInt(r, "limit", 20)
	if err != nil || limit < 1 || limit > 100 {
		http.Error(w, "bad limit", http.StatusBadRequest)
		return
	}
	cursor, err := decodeContactCursor(r.URL.Query().Get("cursor"))
	if err != nil {
		http.Error(w, "bad cursor", http.StatusBadRequest)
		return
	}
	contacts, next, err := s.db.ContactsPageForUser(currentUser(r).Login, limit, cursor)
	if err != nil {
		http.Error(w, "contacts unavailable", http.StatusInternalServerError)
		return
	}
	items := make([]contactResponse, 0, len(contacts))
	for _, contact := range contacts {
		items = append(items, contactJSON(contact))
	}
	writeJSON(w, struct {
		Items      []contactResponse `json:"items"`
		NextCursor string            `json:"next_cursor"`
	}{items, encodeContactCursor(next)})
}

func encodeContactCursor(cursor *state.ContactCursor) string {
	if cursor == nil {
		return ""
	}
	data, _ := json.Marshal(cursor)
	return base64.RawURLEncoding.EncodeToString(data)
}

func decodeContactCursor(value string) (*state.ContactCursor, error) {
	if value == "" {
		return nil, nil
	}
	data, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return nil, err
	}
	var cursor state.ContactCursor
	if err := json.Unmarshal(data, &cursor); err != nil {
		return nil, err
	}
	if cursor.DisplayName == "" || cursor.Login == "" {
		return nil, errors.New("empty cursor")
	}
	return &cursor, nil
}

func (s *Server) contactName(w http.ResponseWriter, r *http.Request) {
	var request struct {
		CustomName string `json:"custom_name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	owner := currentUser(r).Login
	login := r.PathValue("login")
	if err := s.db.SetContactName(owner, login, request.CustomName); err != nil {
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

func (s *Server) contact(w http.ResponseWriter, r *http.Request) {
	owner := currentUser(r).Login
	login := r.PathValue("login")
	switch r.Method {
	case http.MethodGet:
		contact, err := s.db.ContactForUser(owner, login)
		switch {
		case err == nil:
			writeJSON(w, contactJSON(contact))
		case errors.Is(err, sql.ErrNoRows):
			http.Error(w, "contact not found", http.StatusNotFound)
		default:
			http.Error(w, "contacts unavailable", http.StatusInternalServerError)
		}
	case http.MethodPut:
		var request struct {
			CustomName string `json:"custom_name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		contact, err := s.db.AddContact(owner, login, request.CustomName)
		switch {
		case err == nil:
			s.notifyContactChanged(login, owner)
			writeJSON(w, contactJSON(contact))
		case errors.Is(err, state.ErrContactNotFound):
			http.Error(w, "contact not found", http.StatusNotFound)
		case errors.Is(err, state.ErrContactAlreadyExists):
			http.Error(w, "contact already exists", http.StatusConflict)
		case errors.Is(err, state.ErrCannotAddSelf), errors.Is(err, state.ErrInvalidContactName):
			http.Error(w, "bad request", http.StatusBadRequest)
		default:
			http.Error(w, "contacts unavailable", http.StatusInternalServerError)
		}
	case http.MethodDelete:
		if err := s.db.RemoveContact(owner, login); err != nil {
			http.Error(w, "contacts unavailable", http.StatusInternalServerError)
			return
		}
		s.notifyContactChanged(login, owner)
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) notifyContactChanged(recipient, contact string) {
	if s.options.ContactNotifier == nil {
		return
	}
	// Only invalidate an existing entry; this must never act as a contact invitation.
	if _, err := s.db.ContactForUser(recipient, contact); err != nil {
		return
	}
	session, _, err := s.db.CurrentSession(recipient)
	if err != nil {
		return
	}
	go s.options.ContactNotifier.ContactChanged(recipient, contact, session)
}

func contactJSON(contact state.Contact) contactResponse {
	response := contactResponse{
		Login:       contact.Login,
		DisplayName: contact.DisplayName,
		CanCall:     contact.CanCall,
	}
	if contact.CustomName != "" {
		response.CustomName = &contact.CustomName
	}
	return response
}
