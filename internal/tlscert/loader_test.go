package tlscert

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLoaderReloadsRenewedCertificate(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "fullchain.pem")
	keyPath := filepath.Join(dir, "privkey.pem")
	writePair(t, certPath, keyPath, 1)

	loader, err := NewLoader(certPath, keyPath)
	if err != nil {
		t.Fatal(err)
	}
	first, err := loader.GetCertificate(nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := serial(t, first); got != 1 {
		t.Fatalf("initial serial = %d, want 1", got)
	}

	writePair(t, certPath, keyPath, 2)
	second, err := loader.GetCertificate(nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := serial(t, second); got != 2 {
		t.Fatalf("renewed serial = %d, want 2", got)
	}
}

func TestLoaderKeepsLastValidCertificateDuringPartialRenewal(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "fullchain.pem")
	keyPath := filepath.Join(dir, "privkey.pem")
	writePair(t, certPath, keyPath, 7)

	loader, err := NewLoader(certPath, keyPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, []byte("not a private key"), 0600); err != nil {
		t.Fatal(err)
	}

	certificate, err := loader.GetCertificate(nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := serial(t, certificate); got != 7 {
		t.Fatalf("fallback serial = %d, want 7", got)
	}
}

func writePair(t *testing.T, certPath, keyPath string, serialNumber int64) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	template := x509.Certificate{
		SerialNumber: big.NewInt(serialNumber),
		Subject:      pkix.Name{CommonName: "calls.example.com"},
		DNSNames:     []string{"calls.example.com"},
		NotBefore:    now.Add(-time.Minute),
		NotAfter:     now.Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	if err := os.WriteFile(certPath, certPEM, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, keyPEM, 0600); err != nil {
		t.Fatal(err)
	}
}

func serial(t *testing.T, certificate *tls.Certificate) int64 {
	t.Helper()
	leaf, err := x509.ParseCertificate(certificate.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	return leaf.SerialNumber.Int64()
}
