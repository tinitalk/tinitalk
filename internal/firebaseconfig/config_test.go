package firebaseconfig

import (
	"encoding/json"
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
	if config.ConfigID != "sha256:03da49b2ec94c0051bb249542e7b2ae35ba29a7ced5afdd790c2a8eab3bfb793" {
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
	config, err := Parse([]byte(`{"project_info":{"project_number":"123","project_id":"demo"},"client":[{"client_info":{"mobilesdk_app_id":"1:123:android:abc","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"key"}]}]}`))
	if err != nil {
		t.Fatal(err)
	}
	if err := ValidatePair([]byte(`{"project_id":"other"}`), config); err == nil {
		t.Fatal("mismatched service-account project accepted")
	}
}
