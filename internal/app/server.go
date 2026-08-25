package app

import (
	"context"
	"net/http"
	"time"

	"tinitalk/internal/httpapi"
	"tinitalk/internal/state"
)

type ServerConfig struct {
	Addr                  string
	AllowInsecureLoopback bool
}

func NewHTTPServer(db *state.DB, config ServerConfig) *http.Server {
	addr := config.Addr
	if addr == "" {
		addr = ":8080"
	}
	return &http.Server{
		Addr:              addr,
		Handler:           httpapi.NewServer(db, httpapi.Options{AllowInsecureLoopback: config.AllowInsecureLoopback}),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
}

func Shutdown(ctx context.Context, server *http.Server) error {
	return server.Shutdown(ctx)
}
