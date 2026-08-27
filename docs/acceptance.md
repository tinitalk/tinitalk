# Acceptance checklist

Record device models, Android API levels, network type, server region, and timestamp for every run.

## Server

- `make server`
- `make client`
- `make check`
- `tinitalk doctor --data-dir /var/lib/tinitalk --host calls.example.com --addr :443 --turn-addr :3478 --turn-tls-addr :5349`
- Start `tinitalk serve` with readable `--tls-cert` and `--tls-key` files and verify TCP 5349 is listening.
- Verify the installed systemd unit reports `LimitNOFILE=4096`.
- `tinitalk backup --data-dir /var/lib/tinitalk --out /var/backups/tinitalk/state-test.db`

Expected:

- database integrity and foreign keys are `ok`;
- FCM access is `ok` when a service account is configured;
- TLS is valid for the public hostname;
- `443/tcp`, `3478/udp`, `3478/tcp`, `5349/tcp`, and UDP relay ports
  `49152-49663` are reachable through the VPS firewall/security group;
- backup opens successfully and passes integrity checks.

## Accounts

- Add six household users with `tinitalk user add`.
- Sign in on six Android sessions.
- Rotate one token and confirm the old token no longer works.

## Calls

- Make a direct two-way audio call.
- Make a forced TURN relay call and confirm that `local_candidate_type` or
  `remote_candidate_type` is `relay` in redacted WebRTC diagnostics.
- Start three short calls sequentially with different user pairs.
- Verify accept, reject, cancel, mute, audio route, and hangup.
- Swipe away the app, lock the callee phone, wait for Doze, then place a call and answer from the system notification.
- Repeat with Reject from the system notification.
- On the target `1 vCPU / 1 GB RAM` VPS, run a separate forced-relay load test
  in stages `8 -> 16 -> 32 -> 40-50` established calls. Treat `128` as the
  allocation ceiling, not as a promise of 128 calls.

## Recovery

- Briefly switch Wi-Fi to mobile data during a call.
- Briefly drop WSS connectivity and confirm replay resumes from the last sequence.
- Restart the server during a call and confirm both phones return to a clean ended state or can immediately place a new call.

## Audio quality

- Run one call from Moscow to London networks.
- Run one call over forced relay with 5% packet loss and 100 ms jitter if a network emulator is available.
- Speech should remain understandable, with no long one-way audio stalls.
