package command

import "testing"

func TestParseServeOptionsRequiresExternalCertificateInProduction(t *testing.T) {
	options, err := parseServeOptions([]string{
		"--addr", ":443",
		"--tls-cert", "/var/lib/tinitalk/tls/fullchain.pem",
		"--tls-key", "/var/lib/tinitalk/tls/privkey.pem",
		"--turn-public-host", "calls.example.com",
		"--turn-public-ip", "203.0.113.10",
	})
	if err != nil {
		t.Fatal(err)
	}
	if options.tlsCert != "/var/lib/tinitalk/tls/fullchain.pem" || options.tlsKey != "/var/lib/tinitalk/tls/privkey.pem" {
		t.Fatalf("TLS files = %q, %q", options.tlsCert, options.tlsKey)
	}
	if options.turnTLSAddr != ":5349" {
		t.Fatalf("TURN TLS address = %q, want :5349", options.turnTLSAddr)
	}
}

func TestParseServeOptionsRejectsProductionWithoutCertificate(t *testing.T) {
	if _, err := parseServeOptions([]string{"--addr", ":443"}); err == nil {
		t.Fatal("missing TLS files accepted")
	}
}

func TestParseServeOptionsAllowsExplicitInsecureLoopback(t *testing.T) {
	options, err := parseServeOptions([]string{"--loopback-insecure", "--addr", "127.0.0.1:8080"})
	if err != nil {
		t.Fatal(err)
	}
	if !options.allowLoopback {
		t.Fatal("loopback mode was not enabled")
	}
}

func TestParseServeOptionsRejectsInsecureNonLoopbackAddress(t *testing.T) {
	if _, err := parseServeOptions([]string{"--loopback-insecure", "--addr", ":8080"}); err == nil {
		t.Fatal("insecure wildcard listener accepted")
	}
}

func TestParseServeOptionsRequiresTLSForTURN(t *testing.T) {
	if _, err := parseServeOptions([]string{
		"--loopback-insecure", "--addr", "127.0.0.1:8080",
		"--turn-public-host", "calls.example.com",
		"--turn-public-ip", "203.0.113.10",
	}); err == nil {
		t.Fatal("TURN without TLS files accepted")
	}
}
