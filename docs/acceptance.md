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
- WebPush VAPID status is `ok`;
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

## WebPush-only rollout and compatibility

Run the automated checks for each release; the device/deployed-server items below
remain unchecked until recorded external evidence is supplied.

- [ ] From the repository root, run `go test -count=1 ./...`.
- [ ] From `android`, run `./gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease :app:verifyWebRtcJni`.
- [ ] Run the focused legacy-code search below and confirm that matches remain only in historical database migrations, migration tests, allowed WebPush endpoint validation, and the embedded UnifiedPush distributor dependency:

  ```powershell
  git grep -n -i -E 'firebase-config|dynamic_fcm_v1|fcm_token|firebase_installation_id|fcm-service-account|firebase-android-config'
  ```
- [ ] Confirm `/healthz` reports HTTP API `4` and feature `webpush_v1`.
- [ ] Confirm Android 0.9 accepts API 4 and rejects API 3 as outdated.
- [ ] Confirm the server rejects `fcm_token` and `firebase_installation_id` registration payloads.
- [ ] Upgrade a database containing legacy registrations and confirm only WebPush subscriptions remain.
- [ ] Add accounts from two different servers to the same unchanged APK and receive calls from both.
- [ ] Locked screen and Doze: incoming call is presented and can be answered.
- [ ] Reboot, unlock once, do not open the app manually, then receive an incoming call; do not claim Direct Boot delivery before that first unlock.
- [ ] Swipe away or kill the process and receive an incoming call; record separately that Android user Force stop suppresses app delivery by platform design.
- [ ] Rotate or invalidate the WebPush subscription while offline, restore the network, and prove WorkManager uploads the current subscription before release acceptance.
- [ ] Return temporary `5xx` or `429` from `PUT /api/device`, restore the server, and prove retry succeeds.
