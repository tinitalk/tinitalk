package command

import (
	"errors"
	"net"
)

type serveOptions struct {
	addr           string
	allowLoopback  bool
	tlsCert        string
	tlsKey         string
	turnPublicHost string
	turnPublicIP   string
	turnAddr       string
	turnTLSAddr    string
}

func parseServeOptions(args []string) (serveOptions, error) {
	options := serveOptions{addr: ":8080", turnAddr: ":3478", turnTLSAddr: ":5349"}
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
		default:
			return options, errors.New("usage: tinitalk serve --data-dir DIR --tls-cert FILE --tls-key FILE [--addr ADDR] [--turn-public-host HOST --turn-public-ip IP [--turn-addr ADDR] [--turn-tls-addr ADDR]]")
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
	return options, nil
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
