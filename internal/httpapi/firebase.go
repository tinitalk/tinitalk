package httpapi

import "net/http"

func (s *Server) firebaseConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	config := s.options.FirebaseConfig
	writeJSON(w, struct {
		ApplicationID string `json:"application_id"`
		APIKey        string `json:"api_key"`
		ProjectID     string `json:"project_id"`
		GCMSenderID   string `json:"gcm_sender_id"`
		ConfigID      string `json:"config_id"`
	}{
		ApplicationID: config.MobileSDKAppID,
		APIKey:        config.CurrentKey,
		ProjectID:     config.ProjectID,
		GCMSenderID:   config.ProjectNumber,
		ConfigID:      config.ConfigID,
	})
}
