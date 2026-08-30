package command

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"os"
	"path/filepath"
	"testing"
)

func writeFirebaseFiles(t *testing.T, project string) (string, string) {
	t.Helper()
	dir := t.TempDir()
	serviceAccount := filepath.Join(dir, "service-account.json")
	androidConfig := filepath.Join(dir, "google-services.json")
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	encodedKey, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	account, err := json.Marshal(map[string]string{
		"type":           "service_account",
		"project_id":     project,
		"private_key_id": "test-key",
		"private_key":    string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encodedKey})),
		"client_email":   "sender@" + project + ".example.test",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(serviceAccount, account, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(androidConfig, []byte(`{"project_info":{"project_number":"123","project_id":"`+project+`"},"client":[{"client_info":{"mobilesdk_app_id":"1:123:android:abc","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"api-key"}]}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	return serviceAccount, androidConfig
}
