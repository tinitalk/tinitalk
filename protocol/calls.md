# Call Signaling Contract

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
- per-call RAM replay buffer: 256 events.

Control events: `call.start`, `call.incoming`, `call.ringing`, `call.accept`, `call.reject`, `call.cancel`, `call.end`, `call.expire`, `call.resume`.

WebRTC events: `rtc.config`, `rtc.offer`, `rtc.answer`, `rtc.ice`, `rtc.restart`.

The server assigns a monotonic `seq` to delivered events. Re-sending the same `id` must not create a second action. A reconnecting client sends `call.resume` with `last_seq`; the server replays buffered events for the active call after that sequence.
