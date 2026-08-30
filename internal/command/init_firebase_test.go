package command

import (
	"bytes"
	"testing"

	"tinitalk/internal/state"
)

func TestInitStoresMatchingFirebaseFiles(t *testing.T) {
	dir := t.TempDir()
	serviceAccount, androidConfig := writeFirebaseFiles(t, "demo")

	if err := Run(&bytes.Buffer{}, "init", "--data-dir", dir, "--fcm-service-account", serviceAccount, "--firebase-android-config", androidConfig); err != nil {
		t.Fatal(err)
	}
	db, err := state.OpenDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	config, err := db.FirebaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if config.ProjectID != "demo" || config.ConfigID == "" {
		t.Fatalf("Firebase configuration = %+v", config)
	}
}
