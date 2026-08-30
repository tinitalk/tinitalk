package command

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"tinitalk/internal/app"
	"tinitalk/internal/firebaseconfig"
	"tinitalk/internal/httpapi"
	"tinitalk/internal/notify"
	"tinitalk/internal/signaling"
	"tinitalk/internal/state"
	"tinitalk/internal/tlscert"
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
	var fcmServiceAccount, firebaseAndroidConfig []byte
	for len(rest) > 0 {
		if len(rest) < 2 {
			return errors.New("usage: tinitalk init [--data-dir DIR] [--fcm-service-account FILE] [--firebase-android-config FILE]")
		}
		switch rest[0] {
		case "--fcm-service-account":
			fcmServiceAccount, err = os.ReadFile(rest[1])
		case "--firebase-android-config":
			firebaseAndroidConfig, err = os.ReadFile(rest[1])
		default:
			return errors.New("usage: tinitalk init [--data-dir DIR] [--fcm-service-account FILE] [--firebase-android-config FILE]")
		}
		if err != nil {
			return err
		}
		rest = rest[2:]
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := db.Init(fcmServiceAccount, firebaseAndroidConfig); err != nil {
		return err
	}
	_, _ = fmt.Fprintf(w, "state: %s\n", dataDir)
	return nil
}

func runServe(args []string) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	dataDir, rest, err := parseDataDir(args)
	if err != nil {
		return err
	}
	options, err := parseServeOptions(rest)
	if err != nil {
		return err
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := db.RecoverCallHistory(time.Now()); err != nil {
		return err
	}
	notifier := signaling.Notifier(signaling.NoopNotifier{})
	var sessionNotifier httpapi.SessionReplacementNotifier
	fcmServiceAccount, firebaseAndroidConfig, err := loadFirebaseConfiguration(db)
	if err != nil {
		return err
	}
	{
		project := firebaseAndroidConfig.ProjectID
		bearer, err := notify.BearerTokenFromServiceAccount(ctx, fcmServiceAccount)
		if err != nil {
			return err
		}
		sender := notify.HTTPv1Sender{
			Client:      &http.Client{Timeout: notify.RequestTimeout},
			Endpoint:    "https://fcm.googleapis.com/v1/projects/" + project + "/messages:send",
			BearerToken: bearer,
		}
		fcmNotifier := notify.NewFCMNotifier(notify.DBPushTargetStore{DB: db}, sender, project)
		notifier = fcmNotifier
		sessionNotifier = fcmNotifier
	}
	hub := signaling.NewHub(notifier)
	hub.SetCallHistoryStore(db)
	var tlsConfig *tls.Config
	if options.tlsCert != "" {
		loader, err := tlscert.NewLoader(options.tlsCert, options.tlsKey)
		if err != nil {
			return err
		}
		tlsConfig = loader.Config()
	}
	var iceConfig signaling.ICEConfigProvider
	if options.turnPublicHost != "" {
		secret, err := db.Secret("turn_secret")
		if err != nil {
			return err
		}
		if len(secret) == 0 {
			return errors.New("TURN secret is missing; run tinitalk init")
		}
		issuer := turnserver.CredentialIssuer{Secret: secret, TTL: turnserver.DefaultCredentialTTL}
		turn, err := turnserver.Start(turnServerConfig(options, tlsConfig, issuer))
		if err != nil {
			return err
		}
		defer turn.Close()
		iceConfig = turnserver.ICEConfigProvider{PublicHost: options.turnPublicHost, Realm: options.turnPublicHost, Issuer: issuer}
	}
	server := app.NewHTTPServer(db, app.ServerConfig{
		Addr:                  options.addr,
		AllowInsecureLoopback: options.allowLoopback,
		FirebaseConfig:        firebaseAndroidConfig,
		Hub:                   hub,
		SessionNotifier:       sessionNotifier,
		ICEConfigProvider:     iceConfig,
		TLSConfig:             tlsConfig,
	})
	go hub.Run(ctx)
	serverDone := make(chan error, 1)
	go func() {
		if tlsConfig != nil {
			serverDone <- server.ListenAndServeTLS("", "")
			return
		}
		serverDone <- server.ListenAndServe()
	}()

	select {
	case err := <-serverDone:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := app.Shutdown(shutdownContext, server); err != nil {
			return err
		}
		if err := <-serverDone; !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	}
}

func loadFirebaseConfiguration(db *state.DB) ([]byte, firebaseconfig.Config, error) {
	serviceAccount, err := db.Secret("fcm_service_account")
	if err != nil {
		return nil, firebaseconfig.Config{}, err
	}
	if len(serviceAccount) == 0 {
		return nil, firebaseconfig.Config{}, errors.New("FCM service account is missing; run tinitalk init --fcm-service-account FILE")
	}
	config, err := db.FirebaseConfig()
	if err != nil {
		return nil, firebaseconfig.Config{}, err
	}
	if config.ConfigID == "" {
		return nil, firebaseconfig.Config{}, errors.New("FCM Android configuration is missing; run tinitalk init --firebase-android-config FILE")
	}
	if err := firebaseconfig.ValidatePair(serviceAccount, config); err != nil {
		return nil, firebaseconfig.Config{}, err
	}
	return serviceAccount, config, nil
}

func turnServerConfig(options serveOptions, tlsConfig *tls.Config, issuer turnserver.CredentialIssuer) turnserver.Config {
	turnTLSAddr := ""
	if tlsConfig != nil {
		turnTLSAddr = options.turnTLSAddr
	}
	return turnserver.Config{
		PublicIP:              options.turnPublicIP,
		UDPAddr:               options.turnAddr,
		TCPAddr:               options.turnAddr,
		TLSAddr:               turnTLSAddr,
		TLS:                   tlsConfig,
		Relay:                 turnserver.RelayPortRange{Min: options.turnRelayMinPort, Max: options.turnRelayMaxPort},
		Realm:                 options.turnPublicHost,
		Issuer:                issuer,
		MaxAllocations:        options.turnMaxAllocations,
		MaxAllocationsPerUser: options.turnMaxAllocationsPerUser,
		AllocationLifetime:    defaultTURNAllocationLifetime,
	}
}

const defaultDataDir = "/var/lib/tinitalk"

func parseDataDir(args []string) (string, []string, error) {
	dataDir := defaultDataDir
	rest := make([]string, 0, len(args))
	found := false
	for index := 0; index < len(args); index++ {
		if args[index] != "--data-dir" {
			rest = append(rest, args[index])
			continue
		}
		if found {
			return "", nil, errors.New("--data-dir may only be specified once")
		}
		if index+1 >= len(args) {
			return "", nil, errors.New("--data-dir requires DIR")
		}
		dataDir = args[index+1]
		found = true
		index++
	}
	return dataDir, rest, nil
}
