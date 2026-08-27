package command

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"time"
)

const (
	defaultTURNMaxAllocations        = 128
	defaultTURNMaxAllocationsPerUser = 8
	defaultTURNRelayMinPort          = 49152
	defaultTURNRelayMaxPort          = 49663
	defaultTURNAllocationLifetime    = 10 * time.Minute
	turnRelayPortsPerAllocation      = 4
	maxTURNRelayPort                 = 65534
)

type serveOptions struct {
	addr                      string
	allowLoopback             bool
	tlsCert                   string
	tlsKey                    string
	turnPublicHost            string
	turnPublicIP              string
	turnAddr                  string
	turnTLSAddr               string
	turnMaxAllocations        int
	turnMaxAllocationsPerUser int
	turnRelayMinPort          uint16
	turnRelayMaxPort          uint16
}

func parseServeOptions(args []string) (serveOptions, error) {
	options := serveOptions{
		addr:                      ":8080",
		turnAddr:                  ":3478",
		turnTLSAddr:               ":5349",
		turnMaxAllocations:        defaultTURNMaxAllocations,
		turnMaxAllocationsPerUser: defaultTURNMaxAllocationsPerUser,
		turnRelayMinPort:          defaultTURNRelayMinPort,
		turnRelayMaxPort:          defaultTURNRelayMaxPort,
	}
	perUserLimitSet := false
	for len(args) > 0 {
		switch args[0] {
		case "--addr":
			if len(args) < 2 {
				return options, errors.New("--addr requires a value")
			}
			options.addr = args[1]
			args = args[2:]
		case "--loopback-insecure":
			options.allowLoopback = true
			args = args[1:]
		case "--tls-cert":
			if len(args) < 2 {
				return options, errors.New("--tls-cert requires a value")
			}
			options.tlsCert = args[1]
			args = args[2:]
		case "--tls-key":
			if len(args) < 2 {
				return options, errors.New("--tls-key requires a value")
			}
			options.tlsKey = args[1]
			args = args[2:]
		case "--turn-public-host":
			if len(args) < 2 {
				return options, errors.New("--turn-public-host requires a value")
			}
			options.turnPublicHost = args[1]
			args = args[2:]
		case "--turn-public-ip":
			if len(args) < 2 {
				return options, errors.New("--turn-public-ip requires a value")
			}
			options.turnPublicIP = args[1]
			args = args[2:]
		case "--turn-addr":
			if len(args) < 2 {
				return options, errors.New("--turn-addr requires a value")
			}
			options.turnAddr = args[1]
			args = args[2:]
		case "--turn-tls-addr":
			if len(args) < 2 {
				return options, errors.New("--turn-tls-addr requires a value")
			}
			options.turnTLSAddr = args[1]
			args = args[2:]
		case "--turn-max-allocations":
			if len(args) < 2 {
				return options, errors.New("--turn-max-allocations requires a value")
			}
			value, err := strconv.Atoi(args[1])
			if err != nil || value <= 0 {
				return options, errors.New("--turn-max-allocations must be a positive integer")
			}
			options.turnMaxAllocations = value
			args = args[2:]
		case "--turn-max-allocations-per-user":
			if len(args) < 2 {
				return options, errors.New("--turn-max-allocations-per-user requires a value")
			}
			value, err := strconv.Atoi(args[1])
			if err != nil || value <= 0 {
				return options, errors.New("--turn-max-allocations-per-user must be a positive integer")
			}
			options.turnMaxAllocationsPerUser = value
			perUserLimitSet = true
			args = args[2:]
		case "--turn-relay-min-port":
			if len(args) < 2 {
				return options, errors.New("--turn-relay-min-port requires a value")
			}
			port, err := parseTURNRelayPort("--turn-relay-min-port", args[1])
			if err != nil {
				return options, err
			}
			options.turnRelayMinPort = port
			args = args[2:]
		case "--turn-relay-max-port":
			if len(args) < 2 {
				return options, errors.New("--turn-relay-max-port requires a value")
			}
			port, err := parseTURNRelayPort("--turn-relay-max-port", args[1])
			if err != nil {
				return options, err
			}
			options.turnRelayMaxPort = port
			args = args[2:]
		default:
			return options, errors.New("usage: tinitalk serve --tls-cert FILE --tls-key FILE [--data-dir DIR] [--addr ADDR] [--turn-public-host HOST --turn-public-ip IP [--turn-addr ADDR] [--turn-tls-addr ADDR] [--turn-max-allocations N] [--turn-max-allocations-per-user N] [--turn-relay-min-port PORT] [--turn-relay-max-port PORT]]")
		}
	}
	if (options.tlsCert == "") != (options.tlsKey == "") {
		return options, errors.New("--tls-cert and --tls-key must be provided together")
	}
	if !options.allowLoopback && options.tlsCert == "" {
		return options, errors.New("--tls-cert and --tls-key are required outside loopback mode")
	}
	if options.allowLoopback && options.tlsCert == "" && !isLoopbackAddress(options.addr) {
		return options, errors.New("--loopback-insecure requires a loopback listen address")
	}
	if (options.turnPublicHost == "") != (options.turnPublicIP == "") {
		return options, errors.New("TURN requires --turn-public-host and --turn-public-ip")
	}
	if options.turnPublicHost != "" && options.tlsCert == "" {
		return options, errors.New("TURN requires --tls-cert and --tls-key")
	}
	if options.turnRelayMinPort > options.turnRelayMaxPort {
		return options, errors.New("--turn-relay-min-port must not exceed --turn-relay-max-port")
	}
	if options.turnRelayMaxPort%2 == 0 {
		return options, errors.New("--turn-relay-max-port must be odd so EVEN-PORT reservations stay inside the relay range")
	}
	if options.turnMaxAllocationsPerUser > options.turnMaxAllocations {
		if perUserLimitSet {
			return options, errors.New("--turn-max-allocations-per-user must not exceed --turn-max-allocations")
		}
		options.turnMaxAllocationsPerUser = options.turnMaxAllocations
	}
	relayPortCount := int(options.turnRelayMaxPort) - int(options.turnRelayMinPort) + 1
	if options.turnMaxAllocations > relayPortCount/turnRelayPortsPerAllocation {
		return options, fmt.Errorf("TURN relay port range must contain at least %d ports per allocation", turnRelayPortsPerAllocation)
	}
	return options, nil
}

func parseTURNRelayPort(name, value string) (uint16, error) {
	port, err := strconv.ParseUint(value, 10, 16)
	if err != nil || port == 0 || port > maxTURNRelayPort {
		return 0, fmt.Errorf("%s must be an integer between 1 and %d", name, maxTURNRelayPort)
	}
	return uint16(port), nil
}

func isLoopbackAddress(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
