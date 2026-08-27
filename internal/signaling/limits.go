package signaling

import "time"

const (
	ReplayLimit               = 256
	MaxConnectionsPerUser     = 2
	MaxICEPerMinute           = 128
	RestartMinInterval        = 10 * time.Second
	RestartRequestMinInterval = 10 * time.Second
	SweepInterval             = time.Second
	TerminalRetention         = 2 * time.Minute
	ActiveDisconnectGrace     = 30 * time.Second
)
