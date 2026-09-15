# Call Signaling Contract

The current HTTP API version is `4`; the WebSocket signaling protocol version is `2`.

## Browser transport (additive `browser_v1` feature)

Browser HTTP requests use explicit Basic Authorization, `credentials: omit`,
`X-TiniTalk-Device-ID`, and (after login) `X-TiniTalk-Session-ID`. HTTPS origins
are accepted without a central allowlist. Loopback HTTP origins require
`AllowInsecureLoopback`. CORS does not enable cookies; API requests carrying
Origin must include the explicit device header, except the ticket-authenticated
browser WebSocket handshake. OPTIONS is anonymous; API authorization still applies.

`POST /api/browser/session` accepts `{ "device_id": "..." }` without requiring
push permission first. It returns `{ "session_id": "..." }` and retains the
existing one-active-device semantics. The native `/api/session` contract is unchanged.

`POST /api/browser/socket-ticket` requires current session, matching device ID,
and Origin. It returns `{ "ticket": "..." }`. This random ticket expires after
30 seconds, is single-use, and is bound to the exact Origin, credentials and
session. Authentication and session validity are checked again when consumed.

Connect to `/api/browser/socket` with WebSocket subprotocols
`["tinitalk.browser.v1", "ticket.<ticket>"]`. The server selects
`tinitalk.browser.v1` and enables protocol v2 and event acknowledgements.
Do not put credentials or tickets in URLs or log handshake authorization headers.

Browser push registrations use the existing `/api/webpush-config` and
`PUT /api/device`. Subscription JSON includes `client_type: "web"` alongside
`endpoint` and `keys`. Each account uses its own registration and server VAPID
key. The server omits silent `contact_changed` pushes for these subscriptions;
call pushes are visible notifications. Native subscriptions omit `client_type`.

`GET /api/webpush-config` advertises `declarative_web_push: true`. After its
updated account worker is active, an Apple browser subscription may include
`web_app_url`, for example `https://web.example/#account=<local-account-id>`.
This HTTPS URL belongs to the web installation, which may be hosted separately
from the account's family server. It contains only the local account identifier;
credentials and actions are not allowed. Older servers are sent the original
subscription shape, and existing subscriptions are reused when adding metadata.

Apple endpoints receive only `incoming_call` and the visible `session_replaced`
message. In particular, `call_cancel` and `contact_changed` are not sent to them,
including legacy subscriptions without `client_type`. A pending Apple push must
produce a visible notification even if its call has expired, the local account
is unavailable, or the application is already visible. In the latter cases the
worker uses a quiet notification, without presenting an actionable stale call.

Updated Apple subscriptions receive Declarative Web Push JSON:
`{ "web_push": 8030, "notification": { "title": "...", "body": "...",
"navigate": "https://web.example/#account=...&call=...", "tag": "account:call",
"silent": false, "data": { "accountId": "...", "callId": "...",
"sessionId": "...", "tinitalk": { ...original push fields... } } } }`.
WebKit exposes the proposed notification through `PushEvent.notification` with
`event.data === null`; older engines receive the JSON through `event.data`.
The worker handles both shapes. The OS can show and navigate the fallback even
if worker execution fails. Legacy Apple subscriptions keep receiving flat JSON.

Apple web notifications have no Answer/Reject buttons. A tap opens the incoming
call screen; the client checks `/api/active-call` before replaying that call,
including when no inbox record exists. Accepting or ending the call in the app
closes its notification and queues a terminal inbox record in the account worker
to prevent a delayed invite from restoring it. With the PWA closed, an Apple
notification can remain after ringing ends; no invisible cancellation push or
second missed-call notification is sent. Push TTL bounds delivery storage, not
the lifetime of an already displayed notification. Real iOS device validation
is still required for OS delivery, sound and multi-account registrations.

`GET /api/active-call` lets a freshly opened browser client recover an active
call without depending on a notification deep link. It returns `204` when there
is no active call for the authenticated account and `{ "call_id": "..." }` when
the current session/device may resume one with `call.resume`.

`POST /api/calls/{call_id}/reject` (empty body) declines an incoming call from a
notification without opening the app or creating a WebSocket connection. It
requires Basic Authorization, the current managed session and its matching
device ID. Only the callee may reject a known call (`403` otherwise); invalid
call IDs return `400`, and stale/missing sessions or wrong devices return `401`.
Success returns `204`. Unknown, expired, ended or already accepted calls are
safe no-ops, so retries and old notifications cannot interrupt a conversation.
The state check and rejection are atomic with acceptance. A rejection updates
history, delivers `call.reject` to both participants (including replay), and
cancels the incoming push. The worker closes the notification without focusing
or opening a window, retries network/server failures once, and stores a local
`call_cancel` only after acknowledgement. It never replaces a failed background
request with an app launch or another notification. Deploy the server endpoint
alongside the updated worker; older servers do not support this action.

On Android Chromium before M153, notification action buttons can collide
([Chromium issue 534387021](https://crbug.com/534387021)). The web worker uses a
single Reject button on those browsers and explicitly labels the notification
body as Answer. It stores that default action in the notification data, so a body
tap answers only notifications shown in this mode. Other browsers, including
Windows Chrome, retain both buttons where actions are supported. Apple WebKit
uses the body to open the call screen. Ambiguous clicks on older two-button
notifications on affected browsers open the incoming call screen without
automatically accepting or rejecting the call.

`call.incoming.payload.caller_login` contains the server-authenticated caller
login, overriding any caller-supplied value. It is available before a push arrives.
The initial browser client advertises video, SAS and crossed calls as unsupported.

## Event envelope

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

- message size: 32 KiB;
- incoming call wait: 45 seconds;
- simultaneous WebSocket connections per user: 2;
- per-call RAM replay buffer: 256 events;
- `rtc.ice` events per call: 128 per minute;
- minimum interval between `rtc.restart` events per call: 10 seconds;
- minimum interval between `rtc.restart.request` events per call: 10 seconds;
- security-code exchange: 30 seconds from the caller commitment.

Control events: `call.start`, `call.incoming`, `call.ringing`, `call.accept`, `call.connected`, `call.reject`, `call.cancel`, `call.end`, `call.expire`, `call.resume`.

WebRTC events: `rtc.config`, `rtc.offer`, `rtc.answer`, `rtc.ice`, `rtc.video`, `rtc.screen`, `rtc.restart`, `rtc.restart.request`, `rtc.sas.commit`, `rtc.sas.key`, `rtc.sas.reveal`.

Screen sharing is an optional extension. Both bound devices advertise
`supports_exclusive_screen_sharing: true` alongside `supports_video` in `call.start` and
`call.accept` (including crossed starts). Only then does `rtc.config` include
`screen_sharing_allowed: true`. A missing flag means screen sharing is unavailable.
Clients request `rtc.screen` with `enabled` and a UUID `share_id`. The server
allows one presenter and broadcasts the authoritative `enabled`, `presenter_id`
and `share_id` to both devices, initially with `ready: false`. Both clients turn
off camera capture and sending, then acknowledge native camera release with
`rtc.screen.ready {share_id}`. Only after both acknowledgements does the server
broadcast `ready: true`, allowing the presenter to start capture. Preparation
expires after 15 seconds without ending the audio call. Cameras stay off after
sharing until explicitly enabled again. A competing start returns `screen_share_busy`.
A stop only releases the matching presenter's share ID. Resume sends the current
screen state after replay. Grants alone must never start capture without a live,
locally approved Android projection request. Older clients receive no screen events.

## Rejection replies

Servers advertising `call_reply_v1` in `/healthz.features` accept an optional
`reply_code` in the existing `call.reject` payload. The only supported values are
`cannot_talk`, `call_me_later`, and `will_call_back`. An absent field preserves the
ordinary `{}` rejection. An explicitly empty, null, non-string, or unknown code
is invalid. The usual callee, ringing-state, and call ownership checks apply.
The `reply_code` key is case-sensitive; unrelated payload fields do not override it.

The server stores the code atomically with outcome `rejected` before acknowledging
or relaying the event. A database failure leaves the call available for retry.
Retries use the original event ID; deduplication and resume replay preserve the
original payload. Both general and contact call history expose optional
`reply_code` to both participants, including after a server restart. Old rows
omit it. Only the stable code is transmitted and persisted; clients localize it.

Clients show reply controls only after confirming this feature on the incoming
call's account server. Unknown support hides the controls. Peer client versions
do not gate them: older clients still receive ordinary `call.reject`, while new
clients show the localized reply. New clients treat absent or unrecognized reply
codes as ordinary rejection. HTTP API 4 and WebSocket protocol 2 remain unchanged.
Deploy the supporting server before updating clients.

## Incoming push timestamps

The `incoming_call` push includes `started_at` and `expires_at` as UTC RFC 3339
timestamps with optional fractional seconds. Both use the server's call start,
not the caller's clock. `started_at` is additive: older clients ignore it and
new clients accept its absence from older servers. Clients must not infer the
start by subtracting a fixed ringing duration from `expires_at`. Until call
history is available, a missing start uses the time the missed call was observed.

## Call security code

The optional `call_sas_v1` health feature protects a call against an active
signaling-server MITM when both users compare the displayed code. A supporting
client advertises `supports_call_sas: true` in `call.start` or `call.accept`.
The server sets `call_sas_allowed: true` in `rtc.config` only when both bound
devices advertised support. Otherwise the Android UI marks the call as not
verified and sends no SAS events. This preserves calls with older servers and
clients while making a downgrade visible.

The caller and callee each create a fresh 32-byte X25519 private key for the
call. The caller commits before learning the callee's key:

1. Caller sends `rtc.sas.commit {"commitment":"<base64url>"}`.
2. Callee sends `rtc.sas.key {"public_key":"<base64url>","fingerprint":"<hex>"}`.
3. Caller sends `rtc.sas.reveal` with the same fields as `rtc.sas.key`.

The server accepts those events once, in that order, from caller, callee and
caller respectively. Keys and commitments use canonical unpadded base64url;
fingerprints use 64 lowercase hex characters. A participant also rejects a
duplicate or out-of-order message locally.

Cryptographic transcripts use four-byte big-endian length prefixes for the
domain and every following field. The commitment is SHA-256 over domain
`tinitalk-call-sas-v1/commit`, ASCII `call_id`, caller public key and caller
DTLS fingerprint. Each fingerprint is the 32-byte SHA-256 fingerprint parsed
from the participant's local or remote WebRTC SDP. Repeated fingerprint lines
must all contain the same value and use SHA-256; other algorithms and malformed
fingerprint attributes fail SAS rather than being ignored. This prevents media-level
SDP attributes from overriding the certificate being checked. WebRTC then verifies
that the certificate used by DTLS matches the remote SDP before the code is displayed.

Both clients calculate X25519 and derive eight bytes with HKDF-SHA256. The salt
is SHA-256 over domain `tinitalk-call-sas-v1/salt` and `call_id`. HKDF info uses
domain `tinitalk-call-sas-v1/code`, followed by `call_id`, caller login, callee
login, caller and callee public keys, and caller and callee fingerprints in
that order. The eight bytes are interpreted as an unsigned big-endian integer
and reduced modulo 10^12. Android displays this value as five base-256 digits,
most significant first, using the fixed table in `CallSecurityEmoji.kt` and the
bundled Twemoji subset font. This mapping preserves every distinct numeric code.

The code appears only after the aggregate PeerConnection state reports connected
(including DTLS), not merely ICE connectivity. It is hidden while transport is
disconnected and is restored only after transport reconnects with unchanged
fingerprints. A transport failure invalidates the code. Users must compare all
five emoji in order; the app does not confirm a match automatically. A timeout,
malformed exchange or fingerprint change invalidates verification. Once SAS has started,
subsequent configurations cannot disable it or clear a security failure. A crossed
call adopts the server's canonical call ID before starting the exchange; subsequent
messages for another call ID are ignored. Closing the call clears retained private
key material and transcript values from application
memory as far as the managed runtime permits.

Matching codes authenticate the current WebRTC endpoints with about 40 bits of
human-verifiable security. They do not hide metadata, prevent denial of service,
or protect a call whose users do not actually compare the code. A malicious
server can suppress support, but the UI then continues to say that the call is
not verified.

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

SAS rejections use `call_sas_timeout`, `call_sas_invalid`, or
`call_sas_unavailable`. They invalidate security verification without ending the
media call. Clients also classify unlabelled rejections of their pending SAS
events this way for compatibility with earlier SAS servers.

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
