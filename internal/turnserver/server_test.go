package turnserver

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net"
	"testing"
	"time"
)

func TestServerStartsAndClosesUDPAndTCPAndTLSListeners(t *testing.T) {
	server, err := Start(Config{
		PublicIP: "127.0.0.1",
		Realm:    "calls.example.com",
		UDPAddr:  "127.0.0.1:0",
		TCPAddr:  "127.0.0.1:0",
		TLSAddr:  "127.0.0.1:0",
		TLS:      selfSignedTLSConfig(t),
		Relay: RelayPortRange{
			Min: 49152,
			Max: 49663,
		},
		Issuer: CredentialIssuer{
			Secret: []byte("secret"),
			TTL:    time.Minute,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(server.turns) != 3 {
		t.Fatalf("TURN transport servers = %d, want 3", len(server.turns))
	}
	if server.AllocationCount() != 0 {
		t.Fatalf("AllocationCount = %d", server.AllocationCount())
	}
	if err := server.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAllocationLimiterEnforcesUserAndTotalLimits(t *testing.T) {
	limiter := NewAllocationLimiter(2, 1)
	events := limiter.EventHandler("udp")
	aliceSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 40001}
	bobSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.2"), Port: 40002}
	carolSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.3"), Port: 40003}

	if !limiter.Allow("udp", "alice", aliceSource) {
		t.Fatal("first alice allocation rejected")
	}
	events.OnAllocationCreated(aliceSource, &net.UDPAddr{}, "UDP", "alice", "calls.example.com", &net.UDPAddr{}, 0)
	if limiter.Allow("udp", "alice", aliceSource) {
		t.Fatal("second alice allocation accepted, want per-user rejection")
	}
	if !limiter.Allow("udp", "bob", bobSource) {
		t.Fatal("first bob allocation rejected")
	}
	events.OnAllocationCreated(bobSource, &net.UDPAddr{}, "UDP", "bob", "calls.example.com", &net.UDPAddr{}, 0)
	if limiter.Allow("udp", "carol", carolSource) {
		t.Fatal("third total allocation accepted, want total rejection")
	}
	events.OnAllocationDeleted(aliceSource, &net.UDPAddr{}, "UDP", "alice", "calls.example.com")
	if !limiter.Allow("udp", "carol", carolSource) {
		t.Fatal("allocation after release rejected")
	}
}

func TestAllocationLimiterRollsBackPendingReservationOnAllocationError(t *testing.T) {
	limiter := NewAllocationLimiter(1, 1)
	events := limiter.EventHandler("udp")
	aliceSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 40001}
	bobSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.2"), Port: 40002}

	if !limiter.Allow("udp", "alice", aliceSource) {
		t.Fatal("alice reservation rejected")
	}
	events.OnAllocationError(aliceSource, &net.UDPAddr{}, "UDP", "relay allocation failed")

	if !limiter.Allow("udp", "bob", bobSource) {
		t.Fatal("failed allocation kept the only quota slot reserved")
	}
}

func TestAllocationLimiterDoesNotRollBackCreatedAllocationOnLaterError(t *testing.T) {
	limiter := NewAllocationLimiter(1, 1)
	events := limiter.EventHandler("udp")
	aliceSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 40001}
	bobSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.2"), Port: 40002}

	if !limiter.Allow("udp", "alice", aliceSource) {
		t.Fatal("alice reservation rejected")
	}
	events.OnAllocationCreated(aliceSource, &net.UDPAddr{}, "UDP", "alice", "calls.example.com", &net.UDPAddr{}, 0)
	events.OnAllocationError(aliceSource, &net.UDPAddr{}, "UDP", "response write failed")

	if limiter.Allow("udp", "bob", bobSource) {
		t.Fatal("error after allocation creation released an active quota slot")
	}
	events.OnAllocationDeleted(aliceSource, &net.UDPAddr{}, "UDP", "alice", "calls.example.com")
	if !limiter.Allow("udp", "bob", bobSource) {
		t.Fatal("deleted allocation kept its quota slot reserved")
	}
}

func TestAllocationLimiterCorrelatesEqualSourcesAcrossTransportScopes(t *testing.T) {
	limiter := NewAllocationLimiter(2, 1)
	tcpEvents := limiter.EventHandler("tcp-3478")
	tlsEvents := limiter.EventHandler("tls-5349")
	sharedSource := &net.TCPAddr{IP: net.ParseIP("192.0.2.1"), Port: 40001}
	bobRetrySource := &net.UDPAddr{IP: net.ParseIP("192.0.2.2"), Port: 40002}
	carolSource := &net.UDPAddr{IP: net.ParseIP("192.0.2.3"), Port: 40003}

	if !limiter.Allow("tcp-3478", "alice", sharedSource) {
		t.Fatal("alice TCP reservation rejected")
	}
	if !limiter.Allow("tls-5349", "bob", sharedSource) {
		t.Fatal("bob TLS reservation rejected")
	}
	tlsEvents.OnAllocationError(sharedSource, &net.TCPAddr{}, "TCP", "relay allocation failed")
	tcpEvents.OnAllocationCreated(sharedSource, &net.TCPAddr{}, "TCP", "alice", "calls.example.com", &net.UDPAddr{}, 0)

	if !limiter.Allow("udp", "bob", bobRetrySource) {
		t.Fatal("failed TLS reservation was not released for bob")
	}
	if limiter.Allow("udp", "carol", carolSource) {
		t.Fatal("active TCP allocation was released by a TLS error")
	}
}

func TestValidateConfigRejectsUnsafeTURNCapacity(t *testing.T) {
	base := Config{
		PublicIP:              "127.0.0.1",
		Realm:                 "calls.example.com",
		Relay:                 RelayPortRange{Min: 49152, Max: 49663},
		MaxAllocations:        128,
		MaxAllocationsPerUser: 2,
	}
	tests := []struct {
		name   string
		mutate func(*Config)
	}{
		{name: "partial relay range", mutate: func(config *Config) { config.Relay.Min = 0 }},
		{name: "reversed relay range", mutate: func(config *Config) { config.Relay.Min, config.Relay.Max = 50001, 50000 }},
		{name: "maximum uint16 relay port", mutate: func(config *Config) { config.Relay.Max = 65535 }},
		{name: "negative total limit", mutate: func(config *Config) { config.MaxAllocations = -1 }},
		{name: "negative per-user limit", mutate: func(config *Config) { config.MaxAllocationsPerUser = -1 }},
		{name: "negative allocation lifetime", mutate: func(config *Config) { config.AllocationLifetime = -time.Second }},
		{name: "per-user above total", mutate: func(config *Config) { config.MaxAllocations, config.MaxAllocationsPerUser = 1, 2 }},
		{name: "fewer relay ports than allocations", mutate: func(config *Config) { config.Relay.Min, config.Relay.Max = 50000, 50126 }},
		{name: "fewer than four relay ports per allocation", mutate: func(config *Config) {
			config.MaxAllocations = 64
			config.Relay.Min, config.Relay.Max = 50000, 50253
		}},
		{name: "even relay max can reserve outside range", mutate: func(config *Config) {
			config.MaxAllocations = 64
			config.Relay.Min, config.Relay.Max = 49999, 50254
		}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			config := base
			test.mutate(&config)
			if err := validateConfig(config); err == nil {
				t.Fatal("unsafe TURN config accepted")
			}
		})
	}
}

func selfSignedTLSConfig(t *testing.T) *tls.Config {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "calls.example.com"},
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatal(err)
	}
	return &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{cert}}
}
