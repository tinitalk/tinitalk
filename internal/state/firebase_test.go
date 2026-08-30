package state

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestInitStoresValidatedFirebaseConfiguration(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	account := firebaseServiceAccount(t, "demo", "first-key")
	if err := db.Init(account, firebaseAndroidConfig("demo", "123", "app-id", "api-key")); err != nil {
		t.Fatal(err)
	}
	config, err := db.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if config.ProjectID != "demo" || config.CurrentKey != "api-key" || config.ConfigID == "" {
		t.Fatalf("stored configuration = %+v", config)
	}
	storedAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	if string(storedAccount) != string(account) {
		t.Fatalf("stored service account = %s", storedAccount)
	}
}

func TestInitRejectsProjectChangeWithoutReplacingKnownGoodFirebaseConfiguration(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(firebaseServiceAccount(t, "demo", "first-key"), firebaseAndroidConfig("demo", "123", "app-id", "api-key")); err != nil {
		t.Fatal(err)
	}
	beforeAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	beforeConfig, err := db.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}

	if err := db.Init(firebaseServiceAccount(t, "other", "rotated-key"), firebaseAndroidConfig("other", "456", "other-app", "other-api-key")); err == nil {
		t.Fatal("Firebase project change accepted")
	}
	afterAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	afterConfig, err := db.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if string(afterAccount) != string(beforeAccount) || afterConfig != beforeConfig {
		t.Fatalf("invalid update replaced configuration: account %s config %+v", afterAccount, afterConfig)
	}
}

func TestInitLegacyServiceAccountAnchorsFirebaseProject(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(nil, nil); err != nil {
		t.Fatal(err)
	}
	legacyAccount := firebaseServiceAccount(t, "legacy", "old-key")
	if _, err := db.sql.Exec("INSERT INTO secrets(key, value) VALUES(?, ?)", "fcm_service_account", legacyAccount); err != nil {
		t.Fatal(err)
	}

	if err := db.Init(firebaseServiceAccount(t, "replacement", "new-key"), firebaseAndroidConfig("replacement", "456", "replacement-app", "replacement-api-key")); err == nil {
		t.Fatal("legacy Firebase service-account project was replaced")
	}
	storedAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	if string(storedAccount) != string(legacyAccount) {
		t.Fatalf("legacy service account was replaced: %s", storedAccount)
	}
	if err := db.Init(nil, firebaseAndroidConfig("legacy", "123", "legacy-app", "legacy-api-key")); err != nil {
		t.Fatalf("matching Android configuration did not upgrade legacy state: %v", err)
	}
}

func TestInitConcurrentFirebaseProjectsKeepsOneProject(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	seed, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := seed.Init(nil, nil); err != nil {
		_ = seed.Close()
		t.Fatal(err)
	}
	if err := seed.Close(); err != nil {
		t.Fatal(err)
	}

	first, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	if _, err := first.sql.Exec("PRAGMA busy_timeout = 1000"); err != nil {
		t.Fatal(err)
	}
	if _, err := second.sql.Exec("PRAGMA busy_timeout = 1000"); err != nil {
		t.Fatal(err)
	}
	writer, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer writer.Close()
	if _, err := writer.sql.Exec("BEGIN IMMEDIATE"); err != nil {
		t.Fatal(err)
	}

	start := make(chan struct{})
	errs := make(chan error, 2)
	var ready sync.WaitGroup
	ready.Add(2)
	for _, update := range []struct {
		db      *DB
		project string
		account []byte
	}{
		{db: first, project: "first", account: firebaseServiceAccount(t, "first", "key")},
		{db: second, project: "second", account: firebaseServiceAccount(t, "second", "key")},
	} {
		go func(update struct {
			db      *DB
			project string
			account []byte
		}) {
			ready.Done()
			<-start
			errs <- update.db.Init(
				update.account,
				firebaseAndroidConfig(update.project, "123", update.project+"-app", update.project+"-api-key"),
			)
		}(update)
	}
	ready.Wait()
	close(start)
	time.Sleep(100 * time.Millisecond)
	if _, err := writer.sql.Exec("COMMIT"); err != nil {
		t.Fatal(err)
	}

	successes := 0
	for range 2 {
		if err := <-errs; err == nil {
			successes++
		}
	}
	if successes != 1 {
		t.Fatalf("concurrent Firebase project initializations succeeded %d times, want one", successes)
	}
	config, err := first.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if config.ProjectID != "first" && config.ProjectID != "second" {
		t.Fatalf("stored Firebase project = %q", config.ProjectID)
	}
}

func TestInitRejectsInvalidServiceAccountWithoutWritingFreshFirebaseState(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	if err := db.Init([]byte(`{"project_id":"demo"}`), firebaseAndroidConfig("demo", "123", "app-id", "api-key")); err == nil {
		t.Fatal("invalid service account accepted")
	}
	account, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	config, err := db.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if len(account) != 0 || config.ConfigID != "" {
		t.Fatalf("invalid fresh init wrote Firebase state: account=%q config=%+v", account, config)
	}
}

func TestInitInvalidSameProjectRotationKeepsKnownGoodPair(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(firebaseServiceAccount(t, "demo", "first-key"), firebaseAndroidConfig("demo", "123", "app-id", "api-key")); err != nil {
		t.Fatal(err)
	}
	beforeAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	beforeConfig, err := db.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	invalid := []byte(`{"type":"service_account","project_id":"demo","client_email":"sender@example.test","private_key":"not-a-private-key"}`)

	if err := db.Init(invalid, firebaseAndroidConfig("demo", "123", "changed-app", "changed-api-key")); err == nil {
		t.Fatal("invalid same-project rotation accepted")
	}
	afterAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	afterConfig, err := db.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if string(afterAccount) != string(beforeAccount) || afterConfig != beforeConfig {
		t.Fatalf("invalid rotation replaced known-good pair: account=%s config=%+v", afterAccount, afterConfig)
	}
}

func TestInitRotatesValidCredentialsWithinTheSameProject(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(firebaseServiceAccount(t, "demo", "first-key"), firebaseAndroidConfig("demo", "123", "app-id", "api-key")); err != nil {
		t.Fatal(err)
	}
	replacement := firebaseServiceAccount(t, "demo", "rotated-key")
	if err := db.Init(replacement, nil); err != nil {
		t.Fatal(err)
	}
	stored, err := db.Secret("fcm_service_account")
	if err != nil {
		t.Fatal(err)
	}
	if string(stored) != string(replacement) {
		t.Fatal("valid same-project credential rotation was not stored")
	}
}

func firebaseServiceAccount(t *testing.T, project, privateKeyID string) []byte {
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
		"private_key_id": privateKeyID,
		"private_key":    string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encodedKey})),
		"client_email":   "sender@" + project + ".example.test",
	})
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func firebaseAndroidConfig(project, number, appID, key string) []byte {
	return []byte(`{"project_info":{"project_number":"` + number + `","project_id":"` + project + `"},"client":[{"client_info":{"mobilesdk_app_id":"` + appID + `","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"` + key + `"}]}]}`)
}
