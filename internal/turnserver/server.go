package turnserver

import (
	"crypto/tls"
	"errors"
	"io"
	"net"
	"sync"

	"github.com/pion/logging"
	"github.com/pion/turn/v5"
)

type RelayPortRange struct {
	Min uint16
	Max uint16
}

type Config struct {
	PublicIP              string
	UDPAddr               string
	TCPAddr               string
	TLSAddr               string
	TLS                   *tls.Config
	Relay                 RelayPortRange
	Realm                 string
	Issuer                CredentialIssuer
	MaxAllocations        int
	MaxAllocationsPerUser int
}

type Server struct {
	turn *turn.Server
}

func Start(config Config) (*Server, error) {
	if config.PublicIP == "" {
		return nil, errors.New("public IP is required")
	}
	if config.Realm == "" {
		return nil, errors.New("realm is required")
	}
	relayIP := net.ParseIP(config.PublicIP)
	if relayIP == nil {
		return nil, errors.New("public IP is invalid")
	}

	relay := relayGenerator(relayIP, config.Relay)
	packetConfigs, packetClosers, err := packetConnConfigs(config, relay)
	if err != nil {
		return nil, err
	}
	listenerConfigs, listenerClosers, err := streamListenerConfigs(config, relay)
	if err != nil {
		closeAll(packetClosers)
		closeAll(listenerClosers)
		return nil, err
	}
	limiter := NewAllocationLimiter(config.MaxAllocations, config.MaxAllocationsPerUser)
	server, err := turn.NewServer(turn.ServerConfig{
		Realm: config.Realm,
		AuthHandler: func(ra *turn.RequestAttributes) (string, []byte, bool) {
			login, ok := config.Issuer.Login(ra.Username)
			if !ok {
				return "", nil, false
			}
			return login, turn.GenerateAuthKey(ra.Username, config.Realm, config.Issuer.Password(ra.Username)), true
		},
		QuotaHandler:      func(username, _ string, _ net.Addr) bool { return limiter.Allow(username) },
		EventHandler:      limiter.EventHandler(),
		PacketConnConfigs: packetConfigs,
		ListenerConfigs:   listenerConfigs,
		LoggerFactory:     privateLoggerFactory(),
	})
	if err != nil {
		closeAll(packetClosers)
		closeAll(listenerClosers)
		return nil, err
	}
	return &Server{turn: server}, nil
}

func privateLoggerFactory() logging.LoggerFactory {
	return &logging.DefaultLoggerFactory{
		Writer:          io.Discard,
		DefaultLogLevel: logging.LogLevelDisabled,
	}
}

func (s *Server) AllocationCount() int {
	if s == nil || s.turn == nil {
		return 0
	}
	return s.turn.AllocationCount()
}

func (s *Server) Close() error {
	if s == nil || s.turn == nil {
		return nil
	}
	return s.turn.Close()
}

func packetConnConfigs(config Config, relay turn.RelayAddressGenerator) ([]turn.PacketConnConfig, []func() error, error) {
	addr := config.UDPAddr
	if addr == "" {
		addr = "0.0.0.0:3478"
	}
	conn, err := net.ListenPacket("udp4", addr)
	if err != nil {
		return nil, nil, err
	}
	return []turn.PacketConnConfig{{PacketConn: conn, RelayAddressGenerator: relay}}, []func() error{conn.Close}, nil
}

func streamListenerConfigs(config Config, relay turn.RelayAddressGenerator) ([]turn.ListenerConfig, []func() error, error) {
	var configs []turn.ListenerConfig
	var closers []func() error
	if config.TCPAddr != "" {
		listener, err := net.Listen("tcp4", config.TCPAddr)
		if err != nil {
			return nil, closers, err
		}
		configs = append(configs, turn.ListenerConfig{Listener: listener, RelayAddressGenerator: relay})
		closers = append(closers, listener.Close)
	}
	if config.TLSAddr != "" {
		if config.TLS == nil {
			return nil, closers, errors.New("TLS config is required")
		}
		listener, err := tls.Listen("tcp4", config.TLSAddr, config.TLS)
		if err != nil {
			return nil, closers, err
		}
		configs = append(configs, turn.ListenerConfig{Listener: listener, RelayAddressGenerator: relay})
		closers = append(closers, listener.Close)
	}
	return configs, closers, nil
}

func relayGenerator(relayIP net.IP, relay RelayPortRange) turn.RelayAddressGenerator {
	if relay.Min != 0 || relay.Max != 0 {
		return &turn.RelayAddressGeneratorPortRange{
			RelayAddress: relayIP,
			Address:      "0.0.0.0",
			MinPort:      relay.Min,
			MaxPort:      relay.Max,
		}
	}
	return &turn.RelayAddressGeneratorStatic{RelayAddress: relayIP, Address: "0.0.0.0"}
}

func closeAll(closers []func() error) {
	for _, closeFn := range closers {
		_ = closeFn()
	}
}

type AllocationLimiter struct {
	mu      sync.Mutex
	total   int
	limit   int
	perUser int
	users   map[string]int
}

func NewAllocationLimiter(total, perUser int) *AllocationLimiter {
	return &AllocationLimiter{limit: total, perUser: perUser, users: map[string]int{}}
}

func (l *AllocationLimiter) Allow(user string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.limit > 0 && l.total >= l.limit {
		return false
	}
	if l.perUser > 0 && l.users[user] >= l.perUser {
		return false
	}
	l.total++
	l.users[user]++
	return true
}

func (l *AllocationLimiter) Release(user string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.users[user] <= 0 {
		return
	}
	l.users[user]--
	l.total--
}

func (l *AllocationLimiter) EventHandler() turn.EventHandler {
	return turn.EventHandler{
		OnAllocationDeleted: func(_, _ net.Addr, _, userID, _ string) {
			l.Release(userID)
		},
	}
}
