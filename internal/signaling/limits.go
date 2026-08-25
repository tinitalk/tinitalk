package signaling

import "time"

const (
	ReplayLimit           = 256
	MaxConnectionsPerUser = 2
	MaxICEPerMinute       = 32
	SweepInterval         = time.Second
	TerminalRetention     = 2 * time.Minute
)
