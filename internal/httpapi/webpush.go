package httpapi

import "net/http"

func (s *Server) webPushConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, struct {
		VAPIDPublicKey string `json:"vapid_public_key"`
		ConfigID       string `json:"config_id"`
	}{
		VAPIDPublicKey: s.options.WebPushPublicKey,
		ConfigID:       s.options.WebPushConfigID,
	})
}
