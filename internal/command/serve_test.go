package command

import (
	"crypto/tls"
	"testing"
	"time"

	"tinitalk/internal/turnserver"
)

func TestParseServeOptionsRequiresExternalCertificateInProduction(t *testing.T) {
	options, err := parseServeOptions(productionServeArgs())
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

func TestParseServeOptionsUsesTURNCapacityDefaults(t *testing.T) {
	options, err := parseServeOptions(productionServeArgs())
	if err != nil {
		t.Fatal(err)
	}

	if options.turnMaxAllocations != 128 {
		t.Fatalf("TURN max allocations = %d, want 128", options.turnMaxAllocations)
	}
	if options.turnMaxAllocationsPerUser != 8 {
		t.Fatalf("TURN max allocations per user = %d, want 8", options.turnMaxAllocationsPerUser)
	}
	if options.turnRelayMinPort != 49152 || options.turnRelayMaxPort != 49663 {
		t.Fatalf("TURN relay range = %d-%d, want 49152-49663", options.turnRelayMinPort, options.turnRelayMaxPort)
	}
}

func TestParseServeOptionsAcceptsTURNTuning(t *testing.T) {
	options, err := parseServeOptions(productionServeArgs(
		"--turn-max-allocations", "64",
		"--turn-max-allocations-per-user", "6",
		"--turn-relay-min-port", "50000",
		"--turn-relay-max-port", "50255",
	))
	if err != nil {
		t.Fatal(err)
	}

	if options.turnMaxAllocations != 64 {
		t.Fatalf("TURN max allocations = %d, want 64", options.turnMaxAllocations)
	}
	if options.turnMaxAllocationsPerUser != 6 {
		t.Fatalf("TURN max allocations per user = %d, want 6", options.turnMaxAllocationsPerUser)
	}
	if options.turnRelayMinPort != 50000 || options.turnRelayMaxPort != 50255 {
		t.Fatalf("TURN relay range = %d-%d, want 50000-50255", options.turnRelayMinPort, options.turnRelayMaxPort)
	}
}

func TestParseServeOptionsCapsDefaultPerUserQuotaAtTotal(t *testing.T) {
	options, err := parseServeOptions(productionServeArgs("--turn-max-allocations", "4"))
	if err != nil {
		t.Fatal(err)
	}

	if options.turnMaxAllocations != 4 || options.turnMaxAllocationsPerUser != 4 {
		t.Fatalf("TURN limits = %d/%d, want 4/4", options.turnMaxAllocations, options.turnMaxAllocationsPerUser)
	}
}

func TestParseServeOptionsRejectsInvalidTURNTuning(t *testing.T) {
	tests := []struct {
		name  string
		extra []string
	}{
		{name: "missing allocation value", extra: []string{"--turn-max-allocations"}},
		{name: "non numeric allocations", extra: []string{"--turn-max-allocations", "many"}},
		{name: "zero allocations", extra: []string{"--turn-max-allocations", "0"}},
		{name: "missing per-user allocation value", extra: []string{"--turn-max-allocations-per-user"}},
		{name: "non numeric per-user allocations", extra: []string{"--turn-max-allocations-per-user", "many"}},
		{name: "zero per-user allocations", extra: []string{"--turn-max-allocations-per-user", "0"}},
		{name: "per-user allocations above total", extra: []string{"--turn-max-allocations", "4", "--turn-max-allocations-per-user", "5"}},
		{name: "zero relay port", extra: []string{"--turn-relay-min-port", "0"}},
		{name: "overflowing relay port", extra: []string{"--turn-relay-max-port", "65535"}},
		{name: "reversed relay range", extra: []string{"--turn-relay-min-port", "50001", "--turn-relay-max-port", "50000"}},
		{name: "even relay max can reserve outside range", extra: []string{"--turn-max-allocations", "64", "--turn-relay-min-port", "49999", "--turn-relay-max-port", "50254"}},
		{name: "relay range below four ports per allocation", extra: []string{"--turn-max-allocations", "64", "--turn-relay-min-port", "50000", "--turn-relay-max-port", "50253"}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := parseServeOptions(productionServeArgs(test.extra...)); err == nil {
				t.Fatal("invalid TURN tuning accepted")
			}
		})
	}
}

func TestTURNServerConfigUsesServeCapacityOptions(t *testing.T) {
	options, err := parseServeOptions(productionServeArgs(
		"--turn-max-allocations", "64",
		"--turn-max-allocations-per-user", "6",
		"--turn-relay-min-port", "50000",
		"--turn-relay-max-port", "50255",
	))
	if err != nil {
		t.Fatal(err)
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS12}
	issuer := turnserver.CredentialIssuer{Secret: []byte("secret")}

	config := turnServerConfig(options, tlsConfig, issuer)

	if config.MaxAllocations != 64 || config.MaxAllocationsPerUser != 6 {
		t.Fatalf("TURN limits = %d/%d, want 64/6", config.MaxAllocations, config.MaxAllocationsPerUser)
	}
	if config.AllocationLifetime != 10*time.Minute {
		t.Fatalf("TURN allocation lifetime = %s, want 10m", config.AllocationLifetime)
	}
	if config.Relay.Min != 50000 || config.Relay.Max != 50255 {
		t.Fatalf("TURN relay range = %d-%d, want 50000-50255", config.Relay.Min, config.Relay.Max)
	}
	if config.TLS != tlsConfig || config.TLSAddr != ":5349" {
		t.Fatal("TURN TLS config was not propagated")
	}
}

func TestTURNICEConfigProviderUsesServeListenPorts(t *testing.T) {
	options, err := parseServeOptions(productionServeArgs(
		"--turn-addr", ":4433",
		"--turn-tls-addr", "0.0.0.0:4434",
	))
	if err != nil {
		t.Fatal(err)
	}

	provider, err := turnICEConfigProvider(options, turnserver.CredentialIssuer{})
	if err != nil {
		t.Fatal(err)
	}

	if provider.TURNPort != 4433 || provider.TURNTLSPort != 4434 {
		t.Fatalf("ICE TURN ports = %d/%d, want 4433/4434", provider.TURNPort, provider.TURNTLSPort)
	}
}

func TestTURNICEConfigProviderRejectsListenAddressWithoutPort(t *testing.T) {
	options, err := parseServeOptions(productionServeArgs("--turn-addr", "127.0.0.1"))
	if err != nil {
		t.Fatal(err)
	}

	if _, err := turnICEConfigProvider(options, turnserver.CredentialIssuer{}); err == nil {
		t.Fatal("listen address without port accepted")
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

func productionServeArgs(extra ...string) []string {
	args := []string{
		"--addr", ":443",
		"--tls-cert", "/var/lib/tinitalk/tls/fullchain.pem",
		"--tls-key", "/var/lib/tinitalk/tls/privkey.pem",
		"--turn-public-host", "calls.example.com",
		"--turn-public-ip", "203.0.113.10",
	}
	return append(args, extra...)
}
