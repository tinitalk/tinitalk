package command

import (
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"time"

	"tinitalk/internal/notify"
	"tinitalk/internal/state"
)

func runDoctor(w io.Writer, args []string) error {
	dataDir, rest, err := parseDataDir(args)
	if err != nil {
		return err
	}
	host := ""
	addr := ":8080"
	turnAddr := ":3478"
	for len(rest) > 0 {
		switch rest[0] {
		case "--host":
			if len(rest) < 2 {
				return errors.New("--host requires a value")
			}
			host = rest[1]
			rest = rest[2:]
		case "--addr":
			if len(rest) < 2 {
				return errors.New("--addr requires a value")
			}
			addr = rest[1]
			rest = rest[2:]
		case "--turn-addr":
			if len(rest) < 2 {
				return errors.New("--turn-addr requires a value")
			}
			turnAddr = rest[1]
			rest = rest[2:]
		default:
			return errors.New("usage: tinitalk doctor --data-dir DIR [--host HOST] [--addr ADDR] [--turn-addr ADDR]")
		}
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	check, err := db.Check()
	if err != nil {
		return err
	}
	_, _ = fmt.Fprintf(w, "database.integrity: %s\n", ok(check.IntegrityOK))
	_, _ = fmt.Fprintf(w, "database.foreign_keys: %s\n", ok(check.ForeignKeyOK))
	_, _ = fmt.Fprintf(w, "database.schema: %d\n", check.UserVersion)
	for _, key := range []string{"journal_mode", "synchronous", "locking_mode", "foreign_keys"} {
		_, _ = fmt.Fprintf(w, "sqlite.%s: %s\n", key, check.Pragmas[key])
	}
	users, err := db.ListUsers()
	if err != nil {
		return err
	}
	_, _ = fmt.Fprintf(w, "users.count: %d\n", len(users))
	turnSecret, err := db.Secret("turn_secret")
	if err != nil {
		return err
	}
	_, _ = fmt.Fprintf(w, "turn.secret: %s\n", ok(len(turnSecret) > 0))
	fcmServiceAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		return err
	}
	if len(fcmServiceAccount) == 0 {
		_, _ = fmt.Fprintln(w, "fcm.project: missing")
	} else if project, err := notify.ProjectIDFromServiceAccount(fcmServiceAccount); err != nil {
		_, _ = fmt.Fprintln(w, "fcm.project: invalid")
	} else {
		_, _ = fmt.Fprintf(w, "fcm.project: %s\n", project)
	}
	_, _ = fmt.Fprintf(w, "port.http: %s\n", tcpPortStatus(addr))
	_, _ = fmt.Fprintf(w, "port.turn_udp: %s\n", udpPortStatus(turnAddr))
	if host != "" {
		ips, err := net.LookupHost(host)
		if err != nil {
			_, _ = fmt.Fprintf(w, "dns.%s: error\n", host)
		} else {
			_, _ = fmt.Fprintf(w, "dns.%s: %d address(es)\n", host, len(ips))
		}
		_, _ = fmt.Fprintf(w, "tls.%s: %s\n", host, tlsStatus(host))
	}
	return nil
}

func ok(value bool) string {
	if value {
		return "ok"
	}
	return "fail"
}

func tcpPortStatus(addr string) string {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return "busy"
	}
	_ = ln.Close()
	return "free"
}

func udpPortStatus(addr string) string {
	conn, err := net.ListenPacket("udp", addr)
	if err != nil {
		return "busy"
	}
	_ = conn.Close()
	return "free"
}

func tlsStatus(host string) string {
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 5 * time.Second}, "tcp", net.JoinHostPort(host, "443"), &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12})
	if err != nil {
		return "error"
	}
	_ = conn.Close()
	return "ok"
}
