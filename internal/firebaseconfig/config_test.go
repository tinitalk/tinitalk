package firebaseconfig

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"strings"
	"testing"
)

func TestParseSelectsTiniTalkClientAndReturnsOnlyPublicConfiguration(t *testing.T) {
	config, err := Parse([]byte(`{
		"project_info":{"project_number":"123","project_id":"demo"},
		"client":[
			{"client_info":{"mobilesdk_app_id":"other","android_client_info":{"package_name":"example.other"}},"api_key":[{"current_key":"other-key"}]},
			{"client_info":{"mobilesdk_app_id":"1:123:android:abc","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"key"}],"oauth_client":[{"client_id":"private-client-id"}]}
		]
	}`))
	if err != nil {
		t.Fatal(err)
	}
	if config.MobileSDKAppID != "1:123:android:abc" || config.CurrentKey != "key" || config.ProjectID != "demo" || config.ProjectNumber != "123" {
		t.Fatalf("config = %+v", config)
	}
	if config.ConfigID != "sha256:828017481b0f077d83b0e7b9a8096b5451a27182d02b93b1c9e00320d4eedf7a" {
		t.Fatalf("config id = %q", config.ConfigID)
	}
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "private-client-id") {
		t.Fatalf("public configuration leaked private field: %s", raw)
	}
}

func TestParseRejectsMalformedOrAmbiguousAndroidConfiguration(t *testing.T) {
	validClient := `{"client_info":{"mobilesdk_app_id":"1:123:android:abc","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"key"}]}`
	tests := []struct {
		name string
		raw  string
	}{
		{name: "malformed JSON", raw: `{`},
		{name: "wrong package", raw: `{"project_info":{"project_number":"123","project_id":"demo"},"client":[]}`},
		{name: "duplicate package", raw: `{"project_info":{"project_number":"123","project_id":"demo"},"client":[` + validClient + `,` + validClient + `]}`},
		{name: "missing required field", raw: `{"project_info":{"project_number":"123","project_id":"demo"},"client":[{"client_info":{"android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"key"}]}]}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := Parse([]byte(test.raw)); err == nil {
				t.Fatal("invalid Android configuration accepted")
			}
		})
	}
}

func TestValidatePairRejectsServiceAccountFromAnotherProject(t *testing.T) {
	config := testAndroidConfig(t, "demo")
	if err := ValidatePair(testServiceAccount(t, "other", "sender@example.test"), config); err == nil {
		t.Fatal("mismatched service-account project accepted")
	}
}

func TestValidatePairRejectsUnusableServiceAccountCredentials(t *testing.T) {
	config := testAndroidConfig(t, "demo")
	valid := testServiceAccount(t, "demo", "sender@example.test")
	if err := ValidatePair(valid, config); err != nil {
		t.Fatalf("valid service account rejected: %v", err)
	}
	tests := []struct {
		name string
		raw  []byte
	}{
		{name: "missing type", raw: []byte(`{"project_id":"demo"}`)},
		{name: "wrong type", raw: []byte(`{"type":"authorized_user","project_id":"demo"}`)},
		{name: "missing client email", raw: mutateServiceAccount(t, valid, "client_email", "")},
		{name: "missing private key", raw: mutateServiceAccount(t, valid, "private_key", "")},
		{name: "malformed private key", raw: mutateServiceAccount(t, valid, "private_key", "not-a-private-key")},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := ValidatePair(test.raw, config); err == nil {
				t.Fatal("unusable service account accepted")
			}
		})
	}
}

func testAndroidConfig(t *testing.T, project string) Config {
	t.Helper()
	config, err := Parse([]byte(`{"project_info":{"project_number":"123","project_id":"` + project + `"},"client":[{"client_info":{"mobilesdk_app_id":"1:123:android:abc","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"key"}]}]}`))
	if err != nil {
		t.Fatal(err)
	}
	return config
}

func testServiceAccount(t *testing.T, project, email string) []byte {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	encodedKey, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(map[string]string{
		"type":           "service_account",
		"project_id":     project,
		"private_key_id": "test-key",
		"private_key":    string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encodedKey})),
		"client_email":   email,
	})
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func mutateServiceAccount(t *testing.T, raw []byte, key, value string) []byte {
	t.Helper()
	var account map[string]string
	if err := json.Unmarshal(raw, &account); err != nil {
		t.Fatal(err)
	}
	account[key] = value
	mutated, err := json.Marshal(account)
	if err != nil {
		t.Fatal(err)
	}
	return mutated
}
