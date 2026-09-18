package command

import (
	"bytes"
	"testing"
	"time"

	"tinitalk/internal/state"
)

func TestInitTemporaryPasswordTTL(t *testing.T) {
	dir := t.TempDir()
	for _, args := range [][]string{
		{"--temporary-password-ttl", "24h", "--webpush-contact", "https://example.com"},
		{},
	} {
		if err := Run(&bytes.Buffer{}, append([]string{"init", "--data-dir", dir}, args...)...); err != nil {
			t.Fatal(err)
		}
		db, err := state.OpenDir(dir)
		if err != nil {
			t.Fatal(err)
		}
		ttl, err := db.TemporaryPasswordTTL()
		db.Close()
		if err != nil || ttl != 24*time.Hour {
			t.Fatalf("TTL = %v, %v", ttl, err)
		}
	}
	for _, args := range [][]string{
		{"--temporary-password-ttl"}, {"--temporary-password-ttl", "0"},
		{"--temporary-password-ttl", "-1h"}, {"--temporary-password-ttl", "garbage"},
		{"--temporary-password-ttl", "1h", "--temporary-password-ttl", "2h"},
	} {
		if err := Run(&bytes.Buffer{}, append([]string{"init", "--data-dir", dir}, args...)...); err == nil {
			t.Fatalf("accepted invalid TTL arguments %v", args)
		}
	}
}

func TestInitStoresAndPreservesWebPushContact(t *testing.T) {
	dir := t.TempDir()
	const input = "HTTPS://calls.example.com/contact"
	const custom = "https://calls.example.com/contact"

	if err := Run(&bytes.Buffer{}, "init", "--webpush-contact", input, "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	assertWebPushContact(t, dir, custom)

	if err := Run(&bytes.Buffer{}, "init", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}
	assertWebPushContact(t, dir, custom)
}

func TestWebPushContactDefaultsWhenUnset(t *testing.T) {
	dir := t.TempDir()
	if err := Run(&bytes.Buffer{}, "init", "--data-dir", dir); err != nil {
		t.Fatal(err)
	}

	db, err := state.OpenDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	contact, err := loadWebPushContact(db)
	if err != nil {
		t.Fatal(err)
	}
	if contact != defaultWebPushContact {
		t.Fatalf("WebPush contact = %q, want %q", contact, defaultWebPushContact)
	}
}

func TestInitRejectsInvalidWebPushContact(t *testing.T) {
	for _, args := range [][]string{
		{"--webpush-contact"},
		{"--webpush-contact", ""},
		{"--webpush-contact", "calls.example.com"},
		{"--webpush-contact", "http://calls.example.com"},
		{"--webpush-contact", "https://user@calls.example.com"},
	} {
		t.Run(args[len(args)-1], func(t *testing.T) {
			commandArgs := append([]string{"init", "--data-dir", t.TempDir()}, args...)
			if err := Run(&bytes.Buffer{}, commandArgs...); err == nil {
				t.Fatalf("Run(%q) error = nil, want rejection", commandArgs)
			}
		})
	}
}

func assertWebPushContact(t *testing.T, dir, want string) {
	t.Helper()
	db, err := state.OpenDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	got, err := db.Setting(webPushContactSetting)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("stored WebPush contact = %q, want %q", got, want)
	}
}
