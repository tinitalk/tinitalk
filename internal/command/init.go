package command

import (
	"errors"
	"fmt"
	"io"
	"os"

	"tinitalk/internal/app"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

func run(w io.Writer, args []string) (string, error) {
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" || args[0] == "--help" {
		text := "usage: tinitalk init|user|serve\n"
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
		default:
			return errors.New("usage: tinitalk serve --data-dir DIR [--addr ADDR] [--loopback-insecure]")
		}
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	server := app.NewHTTPServer(db, app.ServerConfig{Addr: addr, AllowInsecureLoopback: allowLoopback, Hub: signaling.NewHub(signaling.NoopNotifier{})})
	return server.ListenAndServe()
}

func parseDataDir(args []string) (string, []string, error) {
	if len(args) < 2 || args[0] != "--data-dir" {
		return "", nil, errors.New("--data-dir DIR is required")
	}
	return args[1], args[2:], nil
}
