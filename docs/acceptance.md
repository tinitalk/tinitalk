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
- FCM access is `ok` with the required stored service account and Android configuration;
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

## Dynamic FCM rollout and compatibility

Run the automated checks for each release; the device/deployed-server items below
remain unchecked until recorded external evidence is supplied.

- [ ] From the repository root, run `go test -count=1 ./...`.
- [ ] From `android`, run `./gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease :app:verifyWebRtcJni`.
- [ ] Run the focused stale-doc search below and confirm no stale instruction remains:

  ```powershell
  $stalePattern = (
    'android/app/google-services'
    + '\.json|Для полноценного '
    + 'FCM|APK без '
    + 'него|без'
    + '.*--fcm-service-account|FCM'
    + ' access is .*when|FCM'
    + '.*(optional|опцион|необязател)'
    + '|((optional|опцион|необязател)'
    + '.*)FCM'
  )
  git grep -n -i -E $stalePattern -- README.md docs/acceptance.md
  ```
- [ ] Inspect the release APK with `aapt2` and confirm zero matches for `google_app_id`, `google_api_key`, `gcm_defaultSenderId`, and `default_web_client_id`, without printing values.
- [ ] Verify that the release APK and AAB exist and that neither archive contains `google-services.json`.

- [ ] Old embedded-config APK against the upgraded same-project server: login, token registration, killed process, incoming call, answer, and cancellation.
- [ ] New generic APK against server/project A: fresh install, login, FID activation, killed process, incoming call, answer, and cancellation.
- [ ] The same unchanged APK against server/project B after clearing app data: repeat the full flow without rebuilding.
- [ ] Locked screen and Doze: incoming call is presented and can be answered.
- [ ] Reboot, unlock once, do not open the app manually, then receive an incoming call; do not claim Direct Boot delivery before that first unlock.
- [ ] Swipe away or kill the process and receive an incoming call; record separately that Android user Force stop suppresses app delivery by platform design.
- [ ] Rotate or invalidate the FID while offline, restore the network, and prove WorkManager uploads the current FID before release acceptance.
- [ ] Return temporary `5xx` or `429` from `PUT /api/device`, restore the server, and prove retry succeeds.
- [ ] Confirm `GET /api/firebase-config` occurs during login and is not required while processing each incoming call.
- [ ] Confirm an incoming push starts with a locally initialized default `FirebaseApp` before `TinitalkMessagingService` handles it.
- [ ] Confirm APK resource inspection has no embedded Firebase configuration.
