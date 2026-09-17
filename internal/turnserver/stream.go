package turnserver

import (
	"net"
	"sync"
	"time"

	"github.com/pion/turn/v5"
)

const streamSetupTimeout = 30 * time.Second

// streamGuard bounds TCP and TLS connections, including clients that never
// finish a TLS handshake or create an authenticated TURN allocation.
type streamGuard struct {
	mu           sync.Mutex
	limit        int
	setupTimeout time.Duration
	connections  map[streamKey]*guardedStreamConn
}

type streamKey struct {
	local, remote string
}

func streamAddressKey(local, remote net.Addr) streamKey {
	return streamKey{local.Network() + ":" + local.String(), remote.Network() + ":" + remote.String()}
}

func newStreamGuard(limit int, setupTimeout time.Duration) *streamGuard {
	return &streamGuard{limit: limit, setupTimeout: setupTimeout, connections: make(map[streamKey]*guardedStreamConn)}
}

func (g *streamGuard) wrap(listener net.Listener) net.Listener {
	return &guardedStreamListener{Listener: listener, guard: g}
}

// Use allocation events, not AuthHandler: Pion checks message integrity only
// after AuthHandler returns. UDP events cannot match a TCP address key.
func (g *streamGuard) events(next turn.EventHandler) turn.EventHandler {
	events := next
	events.OnAllocationCreated = func(src, dst net.Addr, protocol, user, realm string, relay net.Addr, port int) {
		next.OnAllocationCreated(src, dst, protocol, user, realm, relay, port)
		g.setAllocated(dst, src, true)
	}
	events.OnAllocationDeleted = func(src, dst net.Addr, protocol, user, realm string) {
		next.OnAllocationDeleted(src, dst, protocol, user, realm)
		g.setAllocated(dst, src, false)
	}
	return events
}

func (g *streamGuard) setAllocated(local, remote net.Addr, allocated bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	conn := g.connections[streamAddressKey(local, remote)]
	if conn == nil {
		return
	}
	// Pion emits events outside its allocation-map lock. A replacement can
	// be created before the previous allocation's deletion callback arrives.
	if allocated {
		conn.allocations++
	} else {
		conn.allocations--
	}
	conn.timer.Stop()
	conn.deadline = time.Time{}
	if conn.allocations <= 0 {
		// Allow a new allocation (and the final Refresh response) on the same
		// connection, but do not leave a deallocated socket open indefinitely.
		conn.deadline = time.Now().Add(g.setupTimeout)
		conn.timer.Reset(g.setupTimeout)
	}
}

type guardedStreamListener struct {
	net.Listener
	guard  *streamGuard
	closed bool // protected by guard.mu
}

func (l *guardedStreamListener) Accept() (net.Conn, error) {
	for {
		raw, err := l.Listener.Accept()
		if err != nil {
			return nil, err
		}
		g := l.guard
		g.mu.Lock()
		if l.closed || len(g.connections) >= g.limit {
			closed := l.closed
			g.mu.Unlock()
			_ = raw.Close()
			if closed {
				return nil, net.ErrClosed
			}
			// Pion stops accepting on ANY error. Reject only this connection.
			continue
		}
		conn := &guardedStreamConn{Conn: raw, owner: l, deadline: time.Now().Add(g.setupTimeout)}
		conn.key = streamAddressKey(raw.LocalAddr(), raw.RemoteAddr())
		g.connections[conn.key] = conn
		conn.timer = time.AfterFunc(g.setupTimeout, conn.expire)
		g.mu.Unlock()
		return conn, nil
	}
}

func (l *guardedStreamListener) Close() error {
	g := l.guard
	g.mu.Lock()
	if l.closed {
		g.mu.Unlock()
		return nil
	}
	l.closed = true
	var connections []*guardedStreamConn
	for _, conn := range g.connections {
		if conn.owner == l {
			connections = append(connections, conn)
		}
	}
	g.mu.Unlock()
	err := l.Listener.Close()
	for _, conn := range connections {
		_ = conn.Close()
	}
	return err
}

type guardedStreamConn struct {
	net.Conn
	owner       *guardedStreamListener
	key         streamKey
	timer       *time.Timer
	deadline    time.Time // zero while an allocation is active; protected by guard.mu
	closed      bool      // protected by guard.mu
	allocations int       // created minus deleted callbacks; protected by guard.mu
}

func (c *guardedStreamConn) expire() {
	g := c.owner.guard
	g.mu.Lock()
	// A callback already running when Stop/Reset was called must not close
	// a newly allocated connection or shorten a new setup window.
	if c.closed || c.deadline.IsZero() || time.Now().Before(c.deadline) {
		g.mu.Unlock()
		return
	}
	c.removeLocked()
	g.mu.Unlock()
	_ = c.Conn.Close()
}

func (c *guardedStreamConn) Close() error {
	g := c.owner.guard
	g.mu.Lock()
	if c.closed {
		g.mu.Unlock()
		return nil
	}
	c.removeLocked()
	g.mu.Unlock()
	return c.Conn.Close()
}

func (c *guardedStreamConn) removeLocked() {
	c.closed = true
	c.timer.Stop()
	delete(c.owner.guard.connections, c.key)
}
