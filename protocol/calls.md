# Call Signaling Contract

The current HTTP API version is `4`; the WebSocket signaling protocol version is `2`.

All signaling messages use one JSON envelope:

```json
{
  "id": "018f7d51-3f90-7e63-b657-4a83a6a90210",
  "call_id": "018f7d51-40a1-7bb5-a2d0-7e47f9181766",
  "type": "call.start",
  "sent_at": 1787666400000,
  "payload": {
    "callee_id": "bob"
  }
}
```

Limits:

- message size: 16 KiB;
- incoming call wait: 45 seconds;
- simultaneous WebSocket connections per user: 2;
- per-call RAM replay buffer: 256 events;
- `rtc.ice` events per call: 128 per minute;
- minimum interval between `rtc.restart` events per call: 10 seconds;
- minimum interval between `rtc.restart.request` events per call: 10 seconds.

Control events: `call.start`, `call.incoming`, `call.ringing`, `call.accept`, `call.connected`, `call.reject`, `call.cancel`, `call.end`, `call.expire`, `call.resume`.

WebRTC events: `rtc.config`, `rtc.offer`, `rtc.answer`, `rtc.ice`, `rtc.restart`, `rtc.restart.request`.

## WebSocket connection

Every client must send `X-TiniTalk-Signal-Protocol: 2` during the WebSocket
upgrade. The server rejects a missing or different version with HTTP `426` and
returns its required version in the same header. A successful upgrade echoes
`X-TiniTalk-Signal-Protocol: 2`. Protocol versions are intentionally strict;
server and Android clients must be updated together.

Clients should send a stable `X-TiniTalk-Device-ID` header. When the same user
opens a new WebSocket with the same non-empty device ID, the server closes and
replaces the older connection. This lets a device reconnect immediately during
a network handover without consuming another per-user connection slot.

Reliable client-to-server delivery is negotiated during the WebSocket upgrade:

1. The client sends `X-TiniTalk-Signal-Ack: 1`.
2. A supporting server echoes `X-TiniTalk-Signal-Ack: 1` in the upgrade
   response.
3. After successfully handling an event, the server sends
   `{"ack":"<event-id>"}`.

Once ACK support is negotiated, the client keeps an outgoing event until its
ACK arrives. After reconnecting, it sends every unacknowledged event again with
the original `id`. The server deduplicates successfully handled action events,
so an ACK lost with the old connection does not repeat the action. `call.resume`
is a replay request rather than an action and is not deduplicated; clients must
ignore replayed events whose `seq` is not newer than the last processed value.

For a locally generated `call.end`, `call.cancel`, or `call.reject`, Android
releases media and Telecom resources immediately but keeps the signaling
service alive until the event is acknowledged or rejected. A 20-second
failsafe bounds this drain if connectivity does not recover.

If a valid event cannot be handled, the server sends an error frame:

```json
{
  "error": "too many ICE events",
  "code": "ice_rate_limited",
  "call_id": "018f7d51-40a1-7bb5-a2d0-7e47f9181766",
  "event_id": "018f7d51-3f90-7e63-b657-4a83a6a90210",
  "retry_after_ms": 1250
}
```

`code` is optional. Rate-limit errors use `ice_rate_limited`,
`ice_restart_rate_limited`, or `ice_restart_request_rate_limited` and include
`retry_after_ms`. The rejected event was not applied; the client may resend it
with the same `id` after that delay. Invalid envelopes return an `error` frame
without event correlation fields.

## ICE restart

Only the caller creates offers and sends `rtc.restart`. If the callee needs an
ICE restart, it sends `rtc.restart.request`; the caller then sends
`rtc.restart`. After forwarding that event, the server sends a fresh
`rtc.config` to both participants. Its `restart_id` equals the `id` of the
`rtc.restart` event. TURN entries in `ice_servers` include an RFC 3339
`expires_at`, so clients can refresh credentials before they expire.

With continual ICE gathering, `rtc.ice` also carries candidate removals. Such an event has `removed: true`, a non-empty `candidates` array, and repeats the first candidate in the top-level ICE fields so older clients can still decode it. `restart_id`, when present, scopes additions and removals to the current ICE generation.

## Replay after reconnect

The server assigns a monotonic `seq` to delivered events. Re-sending the same `id` must not create a second action. A reconnecting client sends `call.resume` with `last_seq`; the server replays buffered events for the active call after that sequence.
