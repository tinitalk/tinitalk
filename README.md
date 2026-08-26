# TiniTalk

Self-hosted Android audio calls for a small household. The server is a single Go binary with HTTPS/WSS signaling, SQLite state, FCM wake-ups, and embedded TURN fallback.

Five useful commands:

```bash
make server
sudo install -m 0755 dist/tinitalk-linux-amd64 /usr/local/bin/tinitalk
sudo -u tinitalk tinitalk init --data-dir /var/lib/tinitalk --fcm-service-account firebase-service-account.json
sudo -u tinitalk tinitalk user add --data-dir /var/lib/tinitalk alice "Alice"
make client
```

Obtain the certificate with Certbot and copy the current pair where the service can read it:

```bash
sudo certbot certonly --standalone -d calls.example.com
sudo install -d -o tinitalk -g tinitalk -m 0700 /var/lib/tinitalk/tls
sudo install -o tinitalk -g tinitalk -m 0644 /etc/letsencrypt/live/calls.example.com/fullchain.pem /var/lib/tinitalk/tls/fullchain.pem
sudo install -o tinitalk -g tinitalk -m 0600 /etc/letsencrypt/live/calls.example.com/privkey.pem /var/lib/tinitalk/tls/privkey.pem
```

Use the same two `install` commands as a Certbot deploy hook after renewal. TiniTalk reads the files on every new TLS connection and starts using the renewed pair without a restart. If renewal temporarily exposes an incomplete pair, the last valid certificate remains active.

Run the server:

```bash
tinitalk serve --data-dir /var/lib/tinitalk --addr :443 \
  --tls-cert /var/lib/tinitalk/tls/fullchain.pem \
  --tls-key /var/lib/tinitalk/tls/privkey.pem \
  --turn-public-host calls.example.com \
  --turn-public-ip 203.0.113.10 \
  --turn-addr :3478 \
  --turn-tls-addr :5349
```

For unattended startup, replace the example hostname and IP in `deploy/tinitalk.service`, then install it:

```bash
sudo install -m 0644 deploy/tinitalk.service /etc/systemd/system/tinitalk.service
sudo systemctl daemon-reload
sudo systemctl enable --now tinitalk
```

Diagnostics and backup:

```bash
tinitalk doctor --data-dir /var/lib/tinitalk --host calls.example.com --addr :443 --turn-addr :3478 --turn-tls-addr :5349
tinitalk backup --data-dir /var/lib/tinitalk --out /var/backups/tinitalk/state-$(date +%F).db
make check
```

VPS notes:

- Open TCP 80 for Certbot standalone renewal, TCP 443 for HTTPS/WSS, TCP/UDP 3478 and TCP 5349 for TURN, and UDP 49160-49200 for relayed media.
- Point the DNS `A` record at the VPS before requesting the certificate.
- Keep `/var/lib/tinitalk/state.db` owned by `tinitalk:tinitalk` and mode `0600`.
- Do not commit Firebase service-account JSON, `state.db`, APKs, or built binaries.
- For updates: stop the service, replace `/usr/local/bin/tinitalk`, run `doctor` as root while the low ports are free, then start the service.
- To restore: stop service, copy a verified backup to `/var/lib/tinitalk/state.db`, fix ownership/mode, start service, run `doctor`.

Android notes:

- Register Android app ID `org.tinitalk` in the same Firebase project, then place its downloaded config at `android/app/google-services.json` before `make client`. The file is ignored by Git. An APK built without it works only while the app is already running; FCM wake-up is unavailable.
- Install `dist/tinitalk-debug.apk`, open the app once, sign in, and complete the microphone, notification, and full-screen incoming-call permission screen.
- Build the relay-only diagnostic APK with `make client GRADLE_ARGS=-PtinitalkForceRelay=true`; rebuild normally afterward.
- Test both direct media and forced TURN relay from the real networks you care about.
