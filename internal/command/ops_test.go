package command

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

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
		"webpush.vapid: ok",
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

func TestHistoryPruneDeletesCallsBeforeDate(t *testing.T) {
	dir := t.TempDir()
	if err := Run(&bytes.Buffer{}, "init", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	if err := Run(&bytes.Buffer{}, "user", "add", "--data-dir", dir, "alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	if err := Run(&bytes.Buffer{}, "user", "add", "--data-dir", dir, "bob", "Bob"); err != nil {
		t.Fatal(err)
	}
	db, err := state.OpenDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	cutoff := time.Date(2026, time.January, 1, 0, 0, 0, 0, time.UTC)
	for callID, startedAt := range map[string]time.Time{
		"old":      cutoff.Add(-time.Second),
		"boundary": cutoff,
		"new":      cutoff.Add(time.Second),
	} {
		if err := db.RecordBusyCall(callID, "alice", "bob", startedAt); err != nil {
			t.Fatal(err)
		}
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	var out bytes.Buffer
	if err := Run(&out, "history", "prune", "--data-dir", dir, "--before", "2026-01-01"); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "history.deleted: 1") {
		t.Fatalf("history prune output = %q", out.String())
	}

	db, err = state.OpenDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	page, err := db.CallHistory("bob", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 2 || page.UnreadMissed != 2 {
		t.Fatalf("history after prune = %+v", page)
	}
	for _, item := range page.Items {
		if item.CallID == "old" {
			t.Fatal("history prune kept a call before the cutoff")
		}
	}
}

func TestHistoryPruneRequiresValidDate(t *testing.T) {
	for _, args := range [][]string{
		{"history", "prune"},
		{"history", "prune", "--before", "01.01.2026"},
	} {
		if err := Run(&bytes.Buffer{}, args...); err == nil {
			t.Fatalf("Run(%q) error = nil", args)
		}
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
