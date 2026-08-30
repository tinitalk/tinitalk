package state

import (
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

	if err := db.Init(firebaseServiceAccount("demo", "first-key"), firebaseAndroidConfig("demo", "123", "app-id", "api-key")); err != nil {
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
	if string(storedAccount) != string(firebaseServiceAccount("demo", "first-key")) {
		t.Fatalf("stored service account = %s", storedAccount)
	}
}

func TestInitRejectsProjectChangeWithoutReplacingKnownGoodFirebaseConfiguration(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Init(firebaseServiceAccount("demo", "first-key"), firebaseAndroidConfig("demo", "123", "app-id", "api-key")); err != nil {
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

	if err := db.Init(firebaseServiceAccount("other", "rotated-key"), firebaseAndroidConfig("other", "456", "other-app", "other-api-key")); err == nil {
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
	legacyAccount := firebaseServiceAccount("legacy", "old-key")
	if _, err := db.sql.Exec("INSERT INTO secrets(key, value) VALUES(?, ?)", "fcm_service_account", legacyAccount); err != nil {
		t.Fatal(err)
	}

	if err := db.Init(firebaseServiceAccount("replacement", "new-key"), firebaseAndroidConfig("replacement", "456", "replacement-app", "replacement-api-key")); err == nil {
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
	}{
		{db: first, project: "first"},
		{db: second, project: "second"},
	} {
		go func(update struct {
			db      *DB
			project string
		}) {
			ready.Done()
			<-start
			errs <- update.db.Init(
				firebaseServiceAccount(update.project, "key"),
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

func firebaseServiceAccount(project, privateKeyID string) []byte {
	return []byte(`{"project_id":"` + project + `","private_key_id":"` + privateKeyID + `"}`)
}

func firebaseAndroidConfig(project, number, appID, key string) []byte {
	return []byte(`{"project_info":{"project_number":"` + number + `","project_id":"` + project + `"},"client":[{"client_info":{"mobilesdk_app_id":"` + appID + `","android_client_info":{"package_name":"org.tinitalk"}},"api_key":[{"current_key":"` + key + `"}]}]}`)
}
