package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"tinitalk/internal/firebaseconfig"
)

func TestFirebaseConfigEndpoint(t *testing.T) {
	db, tokens := testDB(t)
	server := NewServer(db, Options{
		AllowInsecureLoopback: true,
		FirebaseConfig: firebaseconfig.Config{
			MobileSDKAppID: "1:123:android:abc",
			CurrentKey:     "public-api-key",
			ProjectID:      "demo-project",
			ProjectNumber:  "123",
			ConfigID:       "sha256:config",
		},
	})

	tests := []struct {
		name       string
		method     string
		login      string
		token      string
		wantStatus int
	}{
		{name: "authenticated GET", method: http.MethodGet, login: "alice", token: tokens["alice"], wantStatus: http.StatusOK},
		{name: "missing credentials", method: http.MethodGet, wantStatus: http.StatusUnauthorized},
		{name: "invalid credentials", method: http.MethodGet, login: "alice", token: "invalid", wantStatus: http.StatusUnauthorized},
		{name: "non-GET", method: http.MethodPost, login: "alice", token: tokens["alice"], wantStatus: http.StatusMethodNotAllowed},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			req := httptest.NewRequest(test.method, "/api/firebase-config", nil)
			if test.login != "" || test.token != "" {
				req.SetBasicAuth(test.login, test.token)
			}
			response := httptest.NewRecorder()
			server.ServeHTTP(response, req)
			if response.Code != test.wantStatus {
				t.Fatalf("status = %d, body %s, want %d", response.Code, response.Body.String(), test.wantStatus)
			}
			if test.wantStatus != http.StatusOK {
				return
			}

			var got map[string]string
			if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
				t.Fatal(err)
			}
			want := map[string]string{
				"application_id": "1:123:android:abc",
				"api_key":        "public-api-key",
				"project_id":     "demo-project",
				"gcm_sender_id":  "123",
				"config_id":      "sha256:config",
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("response = %#v, want %#v", got, want)
			}
			for _, forbidden := range []string{"mobilesdk_app_id", "current_key", "project_number", "private_key", "client_email", "bearer"} {
				if strings.Contains(response.Body.String(), forbidden) {
					t.Fatalf("response leaked %q: %s", forbidden, response.Body.String())
				}
			}
		})
	}
}
