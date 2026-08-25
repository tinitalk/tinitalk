package turnserver

import (
	"testing"
	"time"
)

func TestServerStartsAndClosesUDPListener(t *testing.T) {
	server, err := Start(Config{
		PublicIP: "127.0.0.1",
		Realm:    "calls.example.com",
		Addr:     "127.0.0.1:0",
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
