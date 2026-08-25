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
	if err := Run(&out, "user", "disable", "--data-dir", dir, "alice"); err != nil {
		t.Fatal(err)
	}
	if err := Run(&out, "user", "rotate-token", "--data-dir", dir, "alice"); err != nil {
		t.Fatal(err)
	}
	if strings.Count(out.String(), "token:") != 1 {
		t.Fatalf("rotate output = %q, want one token", out.String())
	}
	if _, err := RunResult("serve", "--data-dir", filepath.Join(dir, "missing")); err == nil {
		t.Fatal("serve with unsupported flags error = nil, want rejection")
	}
}
