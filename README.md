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

Run the server:

```bash
tinitalk serve --data-dir /var/lib/tinitalk --addr :443 --turn-public-host calls.example.com --turn-public-ip 203.0.113.10 --turn-addr :3478
```

Diagnostics and backup:

```bash
tinitalk doctor --data-dir /var/lib/tinitalk --host calls.example.com --addr :443 --turn-addr :3478
tinitalk backup --data-dir /var/lib/tinitalk --out /var/backups/tinitalk/state-$(date +%F).db
make check
```

VPS notes:

- Open TCP 443 for HTTPS/WSS and TCP/UDP 3478 for TURN.
- Point DNS `A/AAAA` records at the VPS before starting public TLS.
- Keep `/var/lib/tinitalk/state.db` owned by `tinitalk:tinitalk` and mode `0600`.
- Do not commit Firebase service-account JSON, `state.db`, APKs, or built binaries.
- For updates: stop service, replace `/usr/local/bin/tinitalk`, run `doctor`, start service.
- To restore: stop service, copy a verified backup to `/var/lib/tinitalk/state.db`, fix ownership/mode, start service, run `doctor`.

Android notes:

- Put Firebase config into the Android project before building a push-capable APK.
- Install `dist/tinitalk-debug.apk`, open the app once, sign in, and grant microphone/notification permissions.
- Test both direct media and forced TURN relay from the real networks you care about.
