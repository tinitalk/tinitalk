package command

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"tinitalk/internal/app"
	"tinitalk/internal/notify"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
	"tinitalk/internal/turnserver"
)

func run(w io.Writer, args []string) (string, error) {
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" || args[0] == "--help" {
		text := "usage: tinitalk init|user|serve|doctor|backup\n"
		_, _ = io.WriteString(w, text)
		return text, nil
	}
	switch args[0] {
	case "init":
		return "", runInit(w, args[1:])
	case "user":
		return "", runUser(w, args[1:])
	case "serve":
		return "", runServe(args[1:])
	case "doctor":
		return "", runDoctor(w, args[1:])
	case "backup":
		return "", runBackup(w, args[1:])
	default:
		return "", fmt.Errorf("unknown command %q", args[0])
	}
}

func runInit(w io.Writer, args []string) error {
	dataDir, rest, err := parseDataDir(args)
	if err != nil {
		return err
	}
	var fcm []byte
	if len(rest) == 2 && rest[0] == "--fcm-service-account" {
		fcm, err = os.ReadFile(rest[1])
		if err != nil {
			return err
		}
		rest = nil
	}
	if len(rest) != 0 {
		return errors.New("usage: tinitalk init --data-dir DIR [--fcm-service-account FILE]")
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := db.Init(fcm); err != nil {
		return err
	}
	_, _ = fmt.Fprintf(w, "state: %s\n", dataDir)
	return nil
}

func runServe(args []string) error {
	dataDir, rest, err := parseDataDir(args)
	if err != nil {
		return err
	}
	addr := ":8080"
	allowLoopback := false
	turnPublicHost := ""
	turnPublicIP := ""
	turnAddr := ":3478"
	for len(rest) > 0 {
		switch rest[0] {
		case "--addr":
			if len(rest) < 2 {
				return errors.New("--addr requires a value")
			}
			addr = rest[1]
			rest = rest[2:]
		case "--loopback-insecure":
			allowLoopback = true
			rest = rest[1:]
		case "--turn-public-host":
			if len(rest) < 2 {
				return errors.New("--turn-public-host requires a value")
			}
			turnPublicHost = rest[1]
			rest = rest[2:]
		case "--turn-public-ip":
			if len(rest) < 2 {
				return errors.New("--turn-public-ip requires a value")
			}
			turnPublicIP = rest[1]
			rest = rest[2:]
		case "--turn-addr":
			if len(rest) < 2 {
				return errors.New("--turn-addr requires a value")
			}
			turnAddr = rest[1]
			rest = rest[2:]
		default:
			return errors.New("usage: tinitalk serve --data-dir DIR [--addr ADDR] [--loopback-insecure] [--turn-public-host HOST --turn-public-ip IP [--turn-addr ADDR]]")
		}
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	notifier := signaling.Notifier(signaling.NoopNotifier{})
	fcmServiceAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		return err
	}
	if len(fcmServiceAccount) > 0 {
		project, err := notify.ProjectIDFromServiceAccount(fcmServiceAccount)
		if err != nil {
			return err
		}
		bearer, err := notify.BearerTokenFromServiceAccount(context.Background(), fcmServiceAccount)
		if err != nil {
			return err
		}
		sender := notify.HTTPv1Sender{
			Client:      http.DefaultClient,
			Endpoint:    "https://fcm.googleapis.com/v1/projects/" + project + "/messages:send",
			BearerToken: bearer,
		}
		notifier = notify.NewFCMNotifier(notify.DBTokenStore{DB: db}, sender, project)
	}
	hub := signaling.NewHub(notifier)
	var iceConfig signaling.ICEConfigProvider
	if turnPublicHost != "" || turnPublicIP != "" {
		if turnPublicHost == "" || turnPublicIP == "" {
			return errors.New("TURN requires --turn-public-host and --turn-public-ip")
		}
		secret, err := db.Secret("turn_secret")
		if err != nil {
			return err
		}
		issuer := turnserver.CredentialIssuer{Secret: secret, TTL: 10 * time.Minute}
		turn, err := turnserver.Start(turnserver.Config{PublicIP: turnPublicIP, UDPAddr: turnAddr, TCPAddr: turnAddr, Realm: turnPublicHost, Issuer: issuer, Relay: turnserver.RelayPortRange{Min: 49160, Max: 49200}, MaxAllocations: 16, MaxAllocationsPerUser: 2})
		if err != nil {
			return err
		}
		defer turn.Close()
		iceConfig = turnserver.ICEConfigProvider{PublicHost: turnPublicHost, Realm: turnPublicHost, Issuer: issuer}
	}
	server := app.NewHTTPServer(db, app.ServerConfig{Addr: addr, AllowInsecureLoopback: allowLoopback, Hub: hub, ICEConfigProvider: iceConfig})
	return server.ListenAndServe()
}

func parseDataDir(args []string) (string, []string, error) {
	if len(args) < 2 || args[0] != "--data-dir" {
		return "", nil, errors.New("--data-dir DIR is required")
	}
	return args[1], args[2:], nil
}
