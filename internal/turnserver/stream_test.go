package turnserver

import (
	"bytes"
	"crypto/tls"
	"net"
	"testing"
	"time"

	"github.com/pion/stun/v3"
	"github.com/pion/turn/v5"
)

func TestStreamServerCloseReleasesUnallocatedConnections(t *testing.T) {
	for _, transport := range []string{"tcp", "tls"} {
		t.Run(transport, func(t *testing.T) {
			_, listeners, servers := testStreamServers(t, newStreamGuard(2, time.Minute))
			conn := dialTestStream(t, listeners[transport].Addr().String(), transport == "tls")
			// A Binding response proves Pion accepted the socket, without an allocation.
			if err := streamBinding(conn); err != nil {
				t.Fatal(err)
			}
			if err := servers[transport].Close(); err != nil {
				t.Fatal(err)
			}
			assertStreamClosed(t, conn)
		})
	}
}

func TestStreamSetupDeadlineClosesUnauthenticatedPeers(t *testing.T) {
	for _, scenario := range []string{"silent TCP", "silent TLS", "invalid TLS", "idle after TLS", "STUN without allocation"} {
		t.Run(scenario, func(t *testing.T) {
			_, listeners, _ := testStreamServers(t, newStreamGuard(2, 150*time.Millisecond))
			transport := "tcp"
			if scenario != "silent TCP" && scenario != "STUN without allocation" {
				transport = "tls"
			}
			conn := dialTestStream(t, listeners[transport].Addr().String(), scenario == "idle after TLS")
			if scenario == "invalid TLS" {
				if _, err := conn.Write([]byte("not a TLS record")); err != nil {
					t.Fatal(err)
				}
			}
			if scenario == "STUN without allocation" {
				// Successful unauthenticated requests must not prolong the setup window.
				for {
					if err := streamBinding(conn); err != nil {
						if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
							t.Fatal("STUN requests kept an unauthenticated connection open")
						}
						break
					}
				}
			}
			assertStreamClosed(t, conn)
		})
	}
}

func TestStreamLimitIsSharedAndAcceptingResumesAfterClose(t *testing.T) {
	guard := newStreamGuard(1, time.Minute)
	_, listeners, _ := testStreamServers(t, guard)
	first := dialTestStream(t, listeners["tcp"].Addr().String(), false)
	if err := streamBinding(first); err != nil {
		t.Fatal(err)
	}
	// A full TCP budget must reject TLS before spending work on a handshake.
	rejected := dialTestStream(t, listeners["tls"].Addr().String(), false)
	assertStreamClosed(t, rejected)
	_ = first.Close()
	waitForNoStreams(t, guard)
	next := dialTestStream(t, listeners["tls"].Addr().String(), true)
	if err := streamBinding(next); err != nil {
		t.Fatalf("listener stopped accepting after reaching the limit: %v", err)
	}
}

func TestStreamListenersRejectTLSConfigWithoutCertificate(t *testing.T) {
	_, closers, err := streamListenerConfigs(Config{
		TCPAddr: "127.0.0.1:0", TLSAddr: "127.0.0.1:0", TLS: &tls.Config{},
	}, relayGenerator(net.ParseIP("127.0.0.1"), RelayPortRange{}), newStreamGuard(2, time.Second))
	defer closeAll(closers)
	if err == nil {
		t.Fatal("TLS listener started without a certificate or certificate provider")
	}
}

func TestStreamLateAllocationDeletionDoesNotExpireReplacement(t *testing.T) {
	const setupTimeout = 150 * time.Millisecond
	guard := newStreamGuard(1, setupTimeout)
	_, listeners, _ := testStreamServers(t, guard)
	conn := dialTestStream(t, listeners["tcp"].Addr().String(), false)
	if err := streamBinding(conn); err != nil {
		t.Fatal(err)
	}
	// Pion emits deletion outside its allocation-map lock: a replacement's
	// creation callback can arrive before the previous deletion callback.
	guard.setAllocated(conn.RemoteAddr(), conn.LocalAddr(), true)
	guard.setAllocated(conn.RemoteAddr(), conn.LocalAddr(), true)
	guard.setAllocated(conn.RemoteAddr(), conn.LocalAddr(), false)
	time.Sleep(2 * setupTimeout)
	if err := streamBinding(conn); err != nil {
		t.Fatalf("late deletion closed a replacement allocation's connection: %v", err)
	}
	guard.setAllocated(conn.RemoteAddr(), conn.LocalAddr(), false)
	assertStreamClosed(t, conn)
}

func TestStreamAllocationOutlivesSetupAndReleasesConnectionAfterDeallocation(t *testing.T) {
	for _, transport := range []string{"tcp", "tls"} {
		t.Run(transport, func(t *testing.T) {
			const setupTimeout = 300 * time.Millisecond
			guard := newStreamGuard(1, setupTimeout)
			config, listeners, _ := testStreamServers(t, guard)
			addr := listeners[transport].Addr().String()
			conn := dialTestStream(t, addr, transport == "tls")
			credential := config.Issuer.Issue("alice")
			client, err := turn.NewClient(&turn.ClientConfig{
				STUNServerAddr: addr, TURNServerAddr: addr, Conn: turn.NewSTUNConn(conn),
				Username: credential.Username, Password: credential.Password, Realm: config.Realm,
				LoggerFactory: privateLoggerFactory(),
			})
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			if err := client.Listen(); err != nil {
				t.Fatal(err)
			}
			relay, err := client.Allocate()
			if err != nil {
				t.Fatal(err)
			}
			defer relay.Close()
			// Cross the setup deadline while idle: this is not a call-duration limit.
			time.Sleep(2 * setupTimeout)
			peer, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer peer.Close()
			if err := peer.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
				t.Fatal(err)
			}
			if err := relay.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
				t.Fatal(err)
			}
			payload := []byte("audio after setup timeout")
			if _, err := relay.WriteTo(payload, peer.LocalAddr()); err != nil {
				t.Fatal(err)
			}
			buffer := make([]byte, 1500)
			n, from, err := peer.ReadFrom(buffer)
			if err != nil || !bytes.Equal(buffer[:n], payload) {
				t.Fatalf("outbound relay: %q, %v", buffer[:n], err)
			}
			if _, err := peer.WriteTo(payload, from); err != nil {
				t.Fatal(err)
			}
			n, _, err = relay.ReadFrom(buffer)
			if err != nil || !bytes.Equal(buffer[:n], payload) {
				t.Fatalf("inbound relay: %q, %v", buffer[:n], err)
			}
			if err := relay.Close(); err != nil {
				t.Fatal(err)
			}
			waitForNoStreams(t, guard)
			// The client kept TCP open after deallocation; its slot must be reusable.
			next := dialTestStream(t, addr, transport == "tls")
			if err := streamBinding(next); err != nil {
				t.Fatalf("connection slot was not released: %v", err)
			}
		})
	}
}

func testStreamServers(t *testing.T, guard *streamGuard) (Config, map[string]net.Listener, map[string]*turn.Server) {
	t.Helper()
	config := Config{
		Realm: "calls.example.com", Issuer: CredentialIssuer{Secret: []byte("test-secret")},
		TCPAddr: "127.0.0.1:0", TLSAddr: "127.0.0.1:0", TLS: selfSignedTLSConfig(t),
	}
	configs, closers, err := streamListenerConfigs(config, relayGenerator(net.ParseIP("127.0.0.1"), RelayPortRange{}), guard)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { closeAll(closers) })
	listeners := map[string]net.Listener{}
	servers := map[string]*turn.Server{}
	limiter := NewAllocationLimiter(2, 2)
	for i, transport := range []string{"tcp", "tls"} {
		server, err := newTransportServer(config, limiter, guard, transport, nil, []turn.ListenerConfig{configs[i]})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = server.Close() })
		listeners[transport] = configs[i].Listener
		servers[transport] = server
	}
	return config, listeners, servers
}

func dialTestStream(t *testing.T, addr string, encrypted bool) net.Conn {
	t.Helper()
	var conn net.Conn
	var err error
	if encrypted {
		conn, err = tls.DialWithDialer(&net.Dialer{Timeout: 2 * time.Second}, "tcp4", addr, &tls.Config{InsecureSkipVerify: true}) // Test certificate only.
	} else {
		conn, err = net.DialTimeout("tcp4", addr, 2*time.Second)
	}
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := conn.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	return conn
}

func streamBinding(conn net.Conn) error {
	binding := stun.MustBuild(stun.TransactionID, stun.BindingRequest)
	if _, err := conn.Write(binding.Raw); err != nil {
		return err
	}
	_, _, err := turn.NewSTUNConn(conn).ReadFrom(make([]byte, 1500))
	return err
}

func assertStreamClosed(t *testing.T, conn net.Conn) {
	t.Helper()
	if err := conn.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 1500)
	for {
		_, err := conn.Read(buffer)
		if err == nil {
			continue // TLS may have sent an alert before closing the raw socket.
		}
		if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
			t.Fatal("connection remained open")
		}
		return
	}
}

func waitForNoStreams(t *testing.T, guard *streamGuard) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		guard.mu.Lock()
		count := len(guard.connections)
		guard.mu.Unlock()
		if count == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%d connections were not released", count)
		}
		time.Sleep(time.Millisecond)
	}
}
