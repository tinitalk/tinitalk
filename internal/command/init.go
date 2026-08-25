package command

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"

	"tinitalk/internal/app"
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
	if len(args) > 1 {
		return errors.New("usage: tinitalk serve [addr]")
	}
	addr := ":8080"
	if len(args) == 1 {
		addr = args[0]
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(app.HealthPayload()))
	})
	return http.ListenAndServe(addr, mux)
}

func parseDataDir(args []string) (string, []string, error) {
	if len(args) < 2 || args[0] != "--data-dir" {
		return "", nil, errors.New("--data-dir DIR is required")
	}
	return args[1], args[2:], nil
}
