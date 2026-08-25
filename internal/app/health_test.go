package app

import "testing"

func TestHealthPayload(t *testing.T) {
	got := HealthPayload()
	want := `{"status":"ok"}`
	if got != want {
		t.Fatalf("HealthPayload() = %q, want %q", got, want)
	}
}
