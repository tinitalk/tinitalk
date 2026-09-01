package webpush

import "testing"

func TestEmbeddedDistributorEndpointValidation(t *testing.T) {
	if err := validateEndpoint("https://fcm.distributor.unifiedpush.org/wpfcm?t=token"); err != nil {
		t.Fatalf("embedded distributor endpoint rejected: %v", err)
	}
	for _, endpoint := range []string{
		"https://attacker.example/wpfcm?t=token",
		"https://fcm.distributor.unifiedpush.org/other?t=token",
		"https://fcm.distributor.unifiedpush.org/wpfcm?t=token&url=https://attacker.example",
	} {
		if err := validateEndpoint(endpoint); err == nil {
			t.Fatalf("unsafe endpoint accepted: %s", endpoint)
		}
	}
}
