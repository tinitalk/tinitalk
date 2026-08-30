package command

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"
)

func TestInitAndUserCommands(t *testing.T) {
	dir := t.TempDir()
	var out bytes.Buffer

	if err := Run(&out, "init", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	if err := Run(&out, "init", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	if err := Run(&out, "user", "add", "--data-dir", dir, "alice", "Alice"); err != nil {
		t.Fatal(err)
	}
	first := out.String()
	if !strings.Contains(first, "token:") {
		t.Fatalf("output = %q, want token once", first)
	}

	out.Reset()
	if err := Run(&out, "user", "list", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	if got := out.String(); !strings.Contains(got, "alice\tAlice\tenabled") || strings.Contains(got, "token:") {
		t.Fatalf("list output = %q", got)
	}

	out.Reset()
	if err := Run(&out, "user", "rename", "--data-dir", dir, "alice", "Шурик"); err != nil {
		t.Fatal(err)
	}
	if got := out.String(); got != "renamed: alice\n" {
		t.Fatalf("rename output = %q", got)
	}
	out.Reset()
	if err := Run(&out, "user", "list", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	if got := out.String(); !strings.Contains(got, "alice\tШурик\tenabled") {
		t.Fatalf("list after rename output = %q", got)
	}

	out.Reset()
	if err := Run(&out, "user", "disable", "--data-dir", dir, "alice"); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	if err := Run(&out, "user", "enable", "--data-dir", dir, "alice"); err != nil {
		t.Fatal(err)
	}
	if got := out.String(); got != "enabled: alice\n" {
		t.Fatalf("enable output = %q", got)
	}
	out.Reset()
	if err := Run(&out, "user", "list", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	if got := out.String(); !strings.Contains(got, "alice\tШурик\tenabled") {
		t.Fatalf("list after enable output = %q", got)
	}
	out.Reset()
	if err := Run(&out, "user", "rotate-token", "--data-dir", dir, "alice"); err != nil {
		t.Fatal(err)
	}
	if strings.Count(out.String(), "token:") != 1 {
		t.Fatalf("rotate output = %q, want one token", out.String())
	}
	out.Reset()
	if err := Run(&out, "user", "delete", "--data-dir", dir, "alice"); err != nil {
		t.Fatal(err)
	}
	if got := out.String(); got != "deleted: alice\n" {
		t.Fatalf("delete output = %q", got)
	}
	if _, err := RunResult("serve", "--data-dir", filepath.Join(dir, "missing"), "--bad"); err == nil {
		t.Fatal("serve with unsupported flags error = nil, want rejection")
	}
}

func TestParseDataDirDefaultsAndAllowsOverrideAnywhere(t *testing.T) {
	dataDir, rest, err := parseDataDir([]string{"--out", "backup.db"})
	if err != nil {
		t.Fatal(err)
	}
	if dataDir != "/var/lib/tinitalk" || len(rest) != 2 || rest[0] != "--out" || rest[1] != "backup.db" {
		t.Fatalf("parseDataDir default = %q, %v", dataDir, rest)
	}

	dataDir, rest, err = parseDataDir([]string{"--out", "backup.db", "--data-dir", "./data"})
	if err != nil {
		t.Fatal(err)
	}
	if dataDir != "./data" || len(rest) != 2 || rest[0] != "--out" || rest[1] != "backup.db" {
		t.Fatalf("parseDataDir override = %q, %v", dataDir, rest)
	}
}
