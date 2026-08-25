package turnserver

import (
	"errors"
	"net"

	"github.com/pion/turn/v5"
)

type Config struct {
	PublicIP string
	Addr     string
	Realm    string
	Issuer   CredentialIssuer
}

type Server struct {
	turn *turn.Server
	conn net.PacketConn
}

func Start(config Config) (*Server, error) {
	if config.PublicIP == "" {
		return nil, errors.New("public IP is required")
	}
	if config.Realm == "" {
		return nil, errors.New("realm is required")
	}
	addr := config.Addr
	if addr == "" {
		addr = "0.0.0.0:3478"
	}
	conn, err := net.ListenPacket("udp4", addr)
	if err != nil {
		return nil, err
	}
	relayIP := net.ParseIP(config.PublicIP)
	if relayIP == nil {
		_ = conn.Close()
		return nil, errors.New("public IP is invalid")
	}
	server, err := turn.NewServer(turn.ServerConfig{
		Realm: config.Realm,
		AuthHandler: func(ra *turn.RequestAttributes) (string, []byte, bool) {
			if !config.Issuer.ValidUsername(ra.Username) {
				return "", nil, false
			}
			return ra.Username, turn.GenerateAuthKey(ra.Username, config.Realm, config.Issuer.Password(ra.Username)), true
		},
		PacketConnConfigs: []turn.PacketConnConfig{
			{
				PacketConn: conn,
				RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{
					RelayAddress: relayIP,
					Address:      "0.0.0.0",
				},
			},
		},
	})
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	return &Server{turn: server, conn: conn}, nil
}

func (s *Server) AllocationCount() int {
	if s == nil || s.turn == nil {
		return 0
	}
	return s.turn.AllocationCount()
}

func (s *Server) Close() error {
	if s == nil {
		return nil
	}
	if s.turn != nil {
		return s.turn.Close()
	}
	if s.conn != nil {
		return s.conn.Close()
	}
	return nil
}
