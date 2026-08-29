package app

import (
	"context"
	"crypto/tls"
	"net/http"
	"time"

	"tinitalk/internal/httpapi"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
)

type ServerConfig struct {
	Addr                  string
	AllowInsecureLoopback bool
	Hub                   *signaling.Hub
	SessionNotifier       httpapi.SessionReplacementNotifier
	ICEConfigProvider     signaling.ICEConfigProvider
	TLSConfig             *tls.Config
}

func NewHTTPServer(db *state.DB, config ServerConfig) *http.Server {
	addr := config.Addr
	if addr == "" {
		addr = ":8080"
	}
	hub := config.Hub
	if hub != nil && config.ICEConfigProvider != nil {
		hub.SetICEConfigProvider(config.ICEConfigProvider)
	}
	return &http.Server{
		Addr: addr,
		Handler: httpapi.NewServer(db, httpapi.Options{
			AllowInsecureLoopback: config.AllowInsecureLoopback,
			Hub:                   hub,
			SessionNotifier:       config.SessionNotifier,
		}),
		TLSConfig:         config.TLSConfig,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
}

func Shutdown(ctx context.Context, server *http.Server) error {
	return server.Shutdown(ctx)
}
