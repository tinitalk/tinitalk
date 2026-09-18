package httpapi

import "net/http"

func (s *Server) profile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	user := currentUser(r)
	writeJSON(w, struct {
		Login       string `json:"login"`
		DisplayName string `json:"display_name"`
		PasswordSet bool   `json:"password_set"`
	}{
		Login: user.Login,
		// Keep the field for older clients without disclosing the administrative name.
		DisplayName: user.Login,
		PasswordSet: user.PasswordSet,
	})
}
