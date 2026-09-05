package httpapi

import "net/http"

func (s *Server) profile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	user := currentUser(r)
	writeJSON(w, map[string]string{
		"login": user.Login,
		// Keep the field for older clients without disclosing the administrative name.
		"display_name": user.Login,
	})
}
