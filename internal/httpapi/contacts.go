package httpapi

import "net/http"

func (s *Server) contacts(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	user := currentUser(r)
	users, err := s.db.ListUsers()
	if err != nil {
		http.Error(w, "contacts unavailable", http.StatusInternalServerError)
		return
	}
	out := make([]map[string]string, 0, len(users))
	for _, contact := range users {
		if contact.Login == user.Login || contact.Disabled {
			continue
		}
		out = append(out, map[string]string{
			"login":        contact.Login,
			"display_name": contact.DisplayName,
		})
	}
	writeJSON(w, out)
}
