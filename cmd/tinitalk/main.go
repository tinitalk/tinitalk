package main

import (
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"

	"tinitalk/internal/app"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		log.Fatal(err)
	}
}

func run(args []string) error {
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" || args[0] == "--help" {
		fmt.Fprintln(os.Stdout, "usage: tinitalk serve [addr]")
		return nil
	}
	if args[0] != "serve" {
		return fmt.Errorf("unknown command %q", args[0])
	}
	addr := ":8080"
	if len(args) > 1 {
		addr = args[1]
	}
	if len(args) > 2 {
		return errors.New("serve accepts at most one address")
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(app.HealthPayload()))
	})
	return http.ListenAndServe(addr, mux)
}
