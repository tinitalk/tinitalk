package tlscert

import (
	"crypto/tls"
	"sync"
)

type Loader struct {
	certPath string
	keyPath  string

	mu      sync.RWMutex
	current *tls.Certificate
}

func NewLoader(certPath, keyPath string) (*Loader, error) {
	certificate, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, err
	}
	return &Loader{certPath: certPath, keyPath: keyPath, current: &certificate}, nil
}

func (l *Loader) GetCertificate(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	certificate, err := tls.LoadX509KeyPair(l.certPath, l.keyPath)
	if err == nil {
		l.mu.Lock()
		l.current = &certificate
		l.mu.Unlock()
		return &certificate, nil
	}
	l.mu.RLock()
	defer l.mu.RUnlock()
	return l.current, nil
}

func (l *Loader) Config() *tls.Config {
	return &tls.Config{
		MinVersion:     tls.VersionTLS12,
		GetCertificate: l.GetCertificate,
	}
}
