package firebaseconfig

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const (
	AndroidPackageName     = "org.tinitalk"
	firebaseMessagingScope = "https://www.googleapis.com/auth/firebase.messaging"
)

var errStopServiceAccountValidation = errors.New("stop service-account validation before network access")

type Config struct {
	MobileSDKAppID string `json:"mobilesdk_app_id"`
	CurrentKey     string `json:"current_key"`
	ProjectID      string `json:"project_id"`
	ProjectNumber  string `json:"project_number"`
	ConfigID       string `json:"config_id"`
}

type canonicalConfig struct {
	ApplicationID string `json:"application_id"`
	APIKey        string `json:"api_key"`
	ProjectID     string `json:"project_id"`
	GCMSenderID   string `json:"gcm_sender_id"`
}

func Parse(raw []byte) (Config, error) {
	var source struct {
		ProjectInfo struct {
			ProjectID     string `json:"project_id"`
			ProjectNumber string `json:"project_number"`
		} `json:"project_info"`
		Client []struct {
			ClientInfo struct {
				MobileSDKAppID    string `json:"mobilesdk_app_id"`
				AndroidClientInfo struct {
					PackageName string `json:"package_name"`
				} `json:"android_client_info"`
			} `json:"client_info"`
			APIKey []struct {
				CurrentKey string `json:"current_key"`
			} `json:"api_key"`
		} `json:"client"`
	}
	if err := json.Unmarshal(raw, &source); err != nil {
		return Config{}, fmt.Errorf("parse Android Firebase configuration: %w", err)
	}
	if source.ProjectInfo.ProjectID == "" || source.ProjectInfo.ProjectNumber == "" {
		return Config{}, errors.New("Android Firebase configuration requires project_id and project_number")
	}
	var matches []Config
	for _, client := range source.Client {
		if client.ClientInfo.AndroidClientInfo.PackageName != AndroidPackageName {
			continue
		}
		if client.ClientInfo.MobileSDKAppID == "" || len(client.APIKey) == 0 || client.APIKey[0].CurrentKey == "" {
			return Config{}, errors.New("Android Firebase configuration has incomplete org.tinitalk client")
		}
		matches = append(matches, Config{
			MobileSDKAppID: client.ClientInfo.MobileSDKAppID,
			CurrentKey:     client.APIKey[0].CurrentKey,
			ProjectID:      source.ProjectInfo.ProjectID,
			ProjectNumber:  source.ProjectInfo.ProjectNumber,
		})
	}
	if len(matches) != 1 {
		return Config{}, fmt.Errorf("Android Firebase configuration must contain exactly one %s client", AndroidPackageName)
	}
	matches[0].ConfigID = configID(matches[0])
	return matches[0], nil
}

func Validate(config Config) error {
	if config.MobileSDKAppID == "" || config.CurrentKey == "" || config.ProjectID == "" || config.ProjectNumber == "" {
		return errors.New("Android Firebase configuration has missing public fields")
	}
	if config.ConfigID != configID(config) {
		return errors.New("Android Firebase configuration has invalid config_id")
	}
	return nil
}

func ValidatePair(serviceAccount []byte, config Config) error {
	if err := Validate(config); err != nil {
		return err
	}
	projectID, err := ProjectIDFromServiceAccount(serviceAccount)
	if err != nil {
		return err
	}
	if projectID != config.ProjectID {
		return errors.New("Firebase service account and Android configuration projects do not match")
	}
	if err := ValidateServiceAccount(serviceAccount); err != nil {
		return err
	}
	return nil
}

func ValidateServiceAccount(serviceAccount []byte) error {
	config, err := google.JWTConfigFromJSON(serviceAccount, firebaseMessagingScope)
	if err != nil {
		return fmt.Errorf("validate Firebase service account: %w", err)
	}
	if strings.TrimSpace(config.Email) == "" {
		return errors.New("Firebase service account requires client_email")
	}
	transport := &serviceAccountValidationTransport{}
	client := &http.Client{Transport: transport}
	ctx := context.WithValue(context.Background(), oauth2.HTTPClient, client)
	_, err = config.TokenSource(ctx).Token()
	if transport.request != nil {
		return nil
	}
	if err != nil {
		return fmt.Errorf("validate Firebase service account private key: %w", err)
	}
	return errors.New("Firebase service account validation did not attempt a token request")
}

type serviceAccountValidationTransport struct {
	request *http.Request
}

func (transport *serviceAccountValidationTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	transport.request = request
	return nil, errStopServiceAccountValidation
}

func ProjectIDFromServiceAccount(serviceAccount []byte) (string, error) {
	var account struct {
		ProjectID string `json:"project_id"`
	}
	if err := json.Unmarshal(serviceAccount, &account); err != nil {
		return "", fmt.Errorf("parse Firebase service account: %w", err)
	}
	if account.ProjectID == "" {
		return "", errors.New("Firebase service account requires project_id")
	}
	return account.ProjectID, nil
}

func configID(config Config) string {
	canonical, _ := json.Marshal(canonicalConfig{
		ApplicationID: config.MobileSDKAppID,
		APIKey:        config.CurrentKey,
		ProjectID:     config.ProjectID,
		GCMSenderID:   config.ProjectNumber,
	})
	sum := sha256.Sum256(canonical)
	return "sha256:" + hex.EncodeToString(sum[:])
}
