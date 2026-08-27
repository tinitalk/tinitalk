package turnserver

import (
	"crypto/tls"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/pion/logging"
	"github.com/pion/turn/v5"
)

type RelayPortRange struct {
	Min uint16
	Max uint16
}

const relayPortsPerAllocation = 4

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
	AllocationLifetime    time.Duration
}

type Server struct {
	turns []*turn.Server
}

func Start(config Config) (*Server, error) {
	if err := validateConfig(config); err != nil {
		return nil, err
	}
	relayIP := net.ParseIP(config.PublicIP)

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
	turns := make([]*turn.Server, 0, len(packetConfigs)+len(listenerConfigs))
	for _, packetConfig := range packetConfigs {
		scope := allocationScope("packet", packetConfig.PacketConn.LocalAddr())
		server, startErr := newTransportServer(config, limiter, scope, []turn.PacketConnConfig{packetConfig}, nil)
		if startErr != nil {
			closeTURNServers(turns)
			closeAll(packetClosers)
			closeAll(listenerClosers)
			return nil, startErr
		}
		turns = append(turns, server)
	}
	for _, listenerConfig := range listenerConfigs {
		scope := allocationScope("listener", listenerConfig.Listener.Addr())
		server, startErr := newTransportServer(config, limiter, scope, nil, []turn.ListenerConfig{listenerConfig})
		if startErr != nil {
			closeTURNServers(turns)
			closeAll(packetClosers)
			closeAll(listenerClosers)
			return nil, startErr
		}
		turns = append(turns, server)
	}
	return &Server{turns: turns}, nil
}

func newTransportServer(
	config Config,
	limiter *AllocationLimiter,
	scope string,
	packetConfigs []turn.PacketConnConfig,
	listenerConfigs []turn.ListenerConfig,
) (*turn.Server, error) {
	return turn.NewServer(turn.ServerConfig{
		Realm: config.Realm,
		AuthHandler: func(ra *turn.RequestAttributes) (string, []byte, bool) {
			login, ok := config.Issuer.Login(ra.Username)
			if !ok {
				return "", nil, false
			}
			return login, turn.GenerateAuthKey(ra.Username, config.Realm, config.Issuer.Password(ra.Username)), true
		},
		QuotaHandler:       func(username, _ string, source net.Addr) bool { return limiter.Allow(scope, username, source) },
		EventHandler:       limiter.EventHandler(scope),
		AllocationLifetime: config.AllocationLifetime,
		PacketConnConfigs:  packetConfigs,
		ListenerConfigs:    listenerConfigs,
		LoggerFactory:      privateLoggerFactory(),
	})
}

func validateConfig(config Config) error {
	if config.PublicIP == "" {
		return errors.New("public IP is required")
	}
	if config.Realm == "" {
		return errors.New("realm is required")
	}
	if net.ParseIP(config.PublicIP) == nil {
		return errors.New("public IP is invalid")
	}
	if config.MaxAllocations < 0 {
		return errors.New("max allocations must not be negative")
	}
	if config.MaxAllocationsPerUser < 0 {
		return errors.New("max allocations per user must not be negative")
	}
	if config.AllocationLifetime < 0 {
		return errors.New("allocation lifetime must not be negative")
	}
	if config.MaxAllocations > 0 && config.MaxAllocationsPerUser > config.MaxAllocations {
		return errors.New("max allocations per user must not exceed max allocations")
	}
	if config.Relay.Min == 0 && config.Relay.Max == 0 {
		return nil
	}
	if config.Relay.Min == 0 || config.Relay.Max == 0 {
		return errors.New("relay port range requires both min and max")
	}
	if config.Relay.Min > config.Relay.Max {
		return errors.New("relay min port must not exceed max port")
	}
	if config.Relay.Max == ^uint16(0) {
		return errors.New("relay max port must not be 65535")
	}
	if config.Relay.Max%2 == 0 {
		return errors.New("relay max port must be odd")
	}
	relayPortCount := int(config.Relay.Max) - int(config.Relay.Min) + 1
	if config.MaxAllocations > relayPortCount/relayPortsPerAllocation {
		return errors.New("relay port range must contain at least four ports per allocation")
	}
	return nil
}

func privateLoggerFactory() logging.LoggerFactory {
	return &logging.DefaultLoggerFactory{
		Writer:          io.Discard,
		DefaultLogLevel: logging.LogLevelDisabled,
	}
}

func (s *Server) AllocationCount() int {
	if s == nil {
		return 0
	}
	total := 0
	for _, server := range s.turns {
		if server != nil {
			total += server.AllocationCount()
		}
	}
	return total
}

func (s *Server) Close() error {
	if s == nil {
		return nil
	}
	var closeErrors []error
	for _, server := range s.turns {
		if server == nil {
			continue
		}
		if err := server.Close(); err != nil {
			closeErrors = append(closeErrors, err)
		}
	}
	return errors.Join(closeErrors...)
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

func closeTURNServers(servers []*turn.Server) {
	for _, server := range servers {
		_ = server.Close()
	}
}

func allocationScope(kind string, addr net.Addr) string {
	if addr == nil {
		return kind
	}
	return kind + "\x00" + addr.Network() + "\x00" + addr.String()
}

type allocationReservationKey struct {
	scope   string
	network string
	address string
}

type AllocationLimiter struct {
	mu      sync.Mutex
	total   int
	limit   int
	perUser int
	users   map[string]int
	pending map[allocationReservationKey][]string
}

func NewAllocationLimiter(total, perUser int) *AllocationLimiter {
	return &AllocationLimiter{
		limit:   total,
		perUser: perUser,
		users:   map[string]int{},
		pending: map[allocationReservationKey][]string{},
	}
}

func (l *AllocationLimiter) Allow(scope, user string, source net.Addr) bool {
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
	key := allocationSourceKey(scope, source)
	l.pending[key] = append(l.pending[key], user)
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
	if l.users[user] == 0 {
		delete(l.users, user)
	}
}

func (l *AllocationLimiter) EventHandler(scope string) turn.EventHandler {
	return turn.EventHandler{
		OnAllocationCreated: func(source, _ net.Addr, _, userID, _ string, _ net.Addr, _ int) {
			l.confirm(scope, source, userID)
		},
		OnAllocationDeleted: func(_, _ net.Addr, _, userID, _ string) {
			l.Release(userID)
		},
		OnAllocationError: func(source, _ net.Addr, _, _ string) {
			l.rollback(scope, source)
		},
	}
}

func (l *AllocationLimiter) confirm(scope string, source net.Addr, user string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	key := allocationSourceKey(scope, source)
	pending := l.pending[key]
	for index, pendingUser := range pending {
		if pendingUser != user {
			continue
		}
		pending = append(pending[:index], pending[index+1:]...)
		if len(pending) == 0 {
			delete(l.pending, key)
		} else {
			l.pending[key] = pending
		}
		return
	}
}

func (l *AllocationLimiter) rollback(scope string, source net.Addr) {
	l.mu.Lock()
	defer l.mu.Unlock()
	key := allocationSourceKey(scope, source)
	pending := l.pending[key]
	if len(pending) == 0 {
		return
	}
	user := pending[0]
	pending = pending[1:]
	if len(pending) == 0 {
		delete(l.pending, key)
	} else {
		l.pending[key] = pending
	}
	if l.users[user] <= 0 {
		return
	}
	l.users[user]--
	l.total--
	if l.users[user] == 0 {
		delete(l.users, user)
	}
}

func allocationSourceKey(scope string, source net.Addr) allocationReservationKey {
	if source == nil {
		return allocationReservationKey{scope: scope}
	}
	return allocationReservationKey{scope: scope, network: source.Network(), address: source.String()}
}
