package turnserver

import (
	"net"
	"syscall"
	"testing"
)

func TestTURNListenerRequestsReceiveBufferForSharedTraffic(t *testing.T) {
	// Compare real sockets under the same kernel cap. Small custom sizes also
	// detect ignored configuration on hosts whose cap is below the 4 MiB default.
	for _, test := range []struct {
		name          string
		configured    int
		wantRequested int
	}{
		{name: "default", configured: 0, wantRequested: 4194304},
		{name: "custom 64 KiB", configured: 65536, wantRequested: 65536},
		{name: "custom 128 KiB", configured: 131072, wantRequested: 131072},
	} {
		t.Run(test.name, func(t *testing.T) {
			probe, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = probe.Close() })
			if err := probe.SetReadBuffer(test.wantRequested); err != nil {
				t.Fatal(err)
			}
			want := udpReceiveBuffer(t, probe)
			configs, closers, err := packetConnConfigs(Config{
				UDPAddr: "127.0.0.1:0", UDPReadBufferBytes: test.configured,
			}, nil)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { closeAll(closers) })
			got := udpReceiveBuffer(t, configs[0].PacketConn.(*net.UDPConn))
			if got != want {
				t.Fatalf("TURN receive buffer = %d, want %d for request %d", got, want, test.wantRequested)
			}
		})
	}
}

func udpReceiveBuffer(t *testing.T, conn *net.UDPConn) int {
	t.Helper()
	raw, err := conn.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var size int
	var sockErr error
	if err := raw.Control(func(fd uintptr) {
		size, sockErr = syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF)
	}); err != nil {
		t.Fatal(err)
	}
	if sockErr != nil {
		t.Fatal(sockErr)
	}
	return size
}
