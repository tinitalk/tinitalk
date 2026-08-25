package protocol

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDecodeValidFixtures(t *testing.T) {
	for _, name := range []string{"call_start.json", "call_resume.json", "rtc_ice.json"} {
		raw := readFixture(t, name)
		event, err := Decode(raw)
		if err != nil {
			t.Fatalf("%s: Decode() error = %v", name, err)
		}
		if event.ID == "" || event.CallID == "" || event.Type == "" {
			t.Fatalf("%s: decoded event has empty identity fields: %+v", name, event)
		}
		encoded, err := event.Encode()
		if err != nil {
			t.Fatalf("%s: Encode() error = %v", name, err)
		}
		if _, err := Decode(encoded); err != nil {
			t.Fatalf("%s: Decode(Encode()) error = %v", name, err)
		}
	}
}

func TestDecodeRejectsInvalidFixtures(t *testing.T) {
	entries, err := os.ReadDir(filepath.Join("..", "..", "protocol", "testdata", "invalid"))
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		raw := readFixture(t, filepath.Join("invalid", entry.Name()))
		if _, err := Decode(raw); err == nil {
			t.Fatalf("%s: Decode() error = nil, want rejection", entry.Name())
		}
	}
}

func TestDecodeRejectsOversizedPayload(t *testing.T) {
	raw := `{"id":"018f7d51-3f90-7e63-b657-4a83a6a90210","call_id":"018f7d51-40a1-7bb5-a2d0-7e47f9181766","type":"call.start","sent_at":1787666400000,"payload":{"blob":"` + strings.Repeat("a", MaxEventBytes) + `"}}`
	if _, err := Decode([]byte(raw)); err == nil {
		t.Fatal("Decode() error = nil, want oversized payload rejection")
	}
}

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "protocol", "testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return raw
}
