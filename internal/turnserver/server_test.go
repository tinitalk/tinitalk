package turnserver

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
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
			Min: 49160,
			Max: 49200,
		},
		Issuer: CredentialIssuer{
			Secret: []byte("secret"),
			TTL:    time.Minute,
		},
	})
	if err != nil {
		t.Fatal(err)
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

	if !limiter.Allow("alice") {
		t.Fatal("first alice allocation rejected")
	}
	if limiter.Allow("alice") {
		t.Fatal("second alice allocation accepted, want per-user rejection")
	}
	if !limiter.Allow("bob") {
		t.Fatal("first bob allocation rejected")
	}
	if limiter.Allow("carol") {
		t.Fatal("third total allocation accepted, want total rejection")
	}
	limiter.Release("alice")
	if !limiter.Allow("carol") {
		t.Fatal("allocation after release rejected")
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
