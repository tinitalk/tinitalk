package signaling

import "time"

const (
	callStartRateLimitCode         = "call_start_rate_limited"
	iceRateLimitCode               = "ice_rate_limited"
	iceRestartRateLimitCode        = "ice_restart_rate_limited"
	iceRestartRequestRateLimitCode = "ice_restart_request_rate_limited"
)

type ClientError interface {
	error
	Code() string
	RetryAfter() time.Duration
}

type clientError struct {
	message    string
	code       string
	retryAfter time.Duration
}

func (e clientError) Error() string             { return e.message }
func (e clientError) Code() string              { return e.code }
func (e clientError) RetryAfter() time.Duration { return e.retryAfter }
