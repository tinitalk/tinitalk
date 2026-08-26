package command

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"tinitalk/internal/state"
)

func TestDoctorRedactsSecretsAndReportsChecks(t *testing.T) {
	dir := t.TempDir()
	if err := Run(&bytes.Buffer{}, "init", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	if err := Run(&bytes.Buffer{}, "user", "add", "--data-dir", dir, "alice", "Alice"); err != nil {
		t.Fatal(err)
	}

	var out bytes.Buffer
	if err := Run(&out, "doctor", "--data-dir", dir, "--addr", "127.0.0.1:0", "--turn-addr", "127.0.0.1:0", "--turn-tls-addr", "127.0.0.1:0"); err != nil {
		t.Fatal(err)
	}

	got := out.String()
	for _, want := range []string{
		"database.integrity: ok",
		"database.foreign_keys: ok",
		"users.count: 1",
		"turn.secret: ok",
		"fcm.access: missing",
		"port.turn_udp: free",
		"port.turn_tcp: free",
		"port.turn_tls: free",
	} {
		if !strings.Contains(got, want) {
			t.Fatalf("doctor output = %q, want %q", got, want)
		}
	}
	if strings.Contains(got, "token:") {
		t.Fatalf("doctor leaked token-like output: %q", got)
	}
}

func TestBackupCreatesVerifiedCopyAndRefusesOverwrite(t *testing.T) {
	dir := t.TempDir()
	if err := Run(&bytes.Buffer{}, "init", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	backupPath := filepath.Join(t.TempDir(), "state-backup.db")

	var out bytes.Buffer
	if err := Run(&out, "backup", "--data-dir", dir, "--out", backupPath); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(backupPath); err != nil {
		t.Fatal(err)
	}
	backup, err := state.Open(backupPath)
	if err != nil {
		t.Fatal(err)
	}
	defer backup.Close()
	check, err := backup.Check()
	if err != nil {
		t.Fatal(err)
	}
	if !check.IntegrityOK || !check.ForeignKeyOK {
		t.Fatalf("backup check = %+v", check)
	}
	if err := Run(&bytes.Buffer{}, "backup", "--data-dir", dir, "--out", backupPath); err == nil {
		t.Fatal("backup overwrite error = nil, want refusal")
	}
}
