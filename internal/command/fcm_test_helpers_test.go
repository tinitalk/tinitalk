package command

import (
	"os"
	"path/filepath"
	"testing"
)

func writeFirebaseFiles(t *testing.T, project string) (string, string) {
	t.Helper()
	dir := t.TempDir()
	serviceAccount := filepath.Join(dir, "service-account.json")
	androidConfig := filepath.Join(dir, "google-services.json")
	if err := os.WriteFile(serviceAccount, []byte(`{"project_id":"`+project+`","private_key_id":"key"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(androidConfig, []byte(`{"project_info":{"project_number":"123","project_id":"`+project+`"},"client":[{"client_info":{"mobilesdk_app_id":"1:123:android:abc","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"api-key"}]}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	return serviceAccount, androidConfig
}
