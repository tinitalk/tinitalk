# TiniTalk

English | [Русский](README.ru.md)

[![CI](https://github.com/tinitalk/tinitalk/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/tinitalk/tinitalk/actions/workflows/ci.yml?query=branch%3Amain)
[![Release](https://img.shields.io/github/v/release/tinitalk/tinitalk?include_prereleases&sort=semver)](https://github.com/tinitalk/tinitalk/releases)

TiniTalk is a self-hosted app for one-to-one audio and video calls within a
small family or private group. To use it, deploy your own server, create user
accounts, and connect the Android app to it. Calls use WebRTC, either directly
or through the built-in TURN server.

An experimental [web client (PWA)](web/) is available for iPhone.
It supports audio and video calls, accounts on multiple independent servers,
personal contacts, favorites, call history, and Web Push.

## Building

Building the TiniTalk server requires Go 1.26.8 or later, Git, and GNU Make.
Building the Android app also requires JDK 17 and Android SDK Platform 37;
Gradle runs through the wrapper. Configure the JDK through `JAVA_HOME`
or make the `java` command available on `PATH`.

### Development builds

Build the server with:

```bash
make server
```

Output: `dist/tinitalk-linux-amd64`.

Two Android development builds are available, both signed with a local debug key:

```bash
make client
make client-min
```

- `make client` creates `dist/tinitalk-debug.apk` for all supported ABIs.
- `make client-min` creates a smaller `dist/tinitalk-min.apk` for ARM64 only,
  with R8 and resource shrinking enabled.

The APK is signed with a local debug key: `~/.android/debug.keystore` on Linux
and `%USERPROFILE%\.android\debug.keystore` on Windows. Keep this file:
Android will not install an update over an existing app if it is signed with
a different key.

Development builds do not require a release key and are not intended for distribution.

### Release builds

Build the server the same way as a development build: `make server`.

The release APK is signed with a permanent project key. Create this key once,
before the first release, and use it for all subsequent versions.

Create a local directory and generate the keystore:

```bash
mkdir android/keystore
keytool -genkeypair -v -keystore android/keystore/tinitalk-release.jks -alias tinitalk-release -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=TiniTalk, OU=Release Signing, O=TiniTalk Open Source Project"
```

`keytool` will prompt for a keystore password.

Create `android/keystore/release.properties`:

```properties
storeFile=keystore/tinitalk-release.jks
storePassword=PASSWORD
keyAlias=tinitalk-release
keyPassword=PASSWORD
```

Build the release:

```bash
make client-release
```

This command runs unit tests, checks that the WebRTC API is preserved after R8,
and creates a signed ARM64 APK. The version number is taken automatically from
`versionName` in `android/app/build.gradle.kts`:

```text
dist/tinitalk-v0.10.apk
```

The `android/keystore` directory is included in `.gitignore`. Do not commit
`tinitalk-release.jks`, `release.properties`, or passwords to Git.

After creating the key, back up `tinitalk-release.jks` and its passwords in a
secure location. Losing the key prevents you from publishing updates for
existing installations; leaking it allows someone else to sign APKs on behalf
of the project.

## Setting up and running the TiniTalk server

The instructions below assume a VPS running Debian or Ubuntu.

You need a TiniTalk server binary, a TLS certificate, and its private key.
The certificate must match the domain or IP address entered in the Android app
and be trusted by the device. The TiniTalk server must be reachable from the
internet through a public domain or IP address. The built-in TURN server
requires a public IPv4 address on the VPS.

### 1. Create a system user and directories

Run the TiniTalk server as a dedicated system user named `tinitalk`. This keeps
the process from running as root and restricts access to the server's files
to that user.

Directories:

| Path | Purpose |
|---|---|
| `/var/lib/tinitalk` | SQLite database and internal server keys |
| `/var/lib/tinitalk/tls` | TLS certificate and private key |
| `/var/backups/tinitalk` | Backups |

```bash
sudo adduser --system --group --home /var/lib/tinitalk --no-create-home tinitalk
sudo install -d -o tinitalk -g tinitalk -m 0700 \
  /var/lib/tinitalk /var/lib/tinitalk/tls /var/backups/tinitalk
```

### 2. Copy the binary and TLS files

First, copy `tinitalk-linux-amd64` to the VPS and obtain the TLS certificate
and private key files.

Store the TLS files in `/var/lib/tinitalk/tls` so the process running as
`tinitalk` can read them.

```bash
sudo install -m 0755 \
  ./tinitalk-linux-amd64 \
  /usr/local/bin/tinitalk
sudo install -o tinitalk -g tinitalk -m 0644 \
  ./fullchain.pem \
  /var/lib/tinitalk/tls/fullchain.pem
sudo install -o tinitalk -g tinitalk -m 0600 \
  ./privkey.pem \
  /var/lib/tinitalk/tls/privkey.pem
```

### 3. Initialize storage

Before starting the service for the first time, run `tinitalk init` to create
the database, schema, and internal server keys in `/var/lib/tinitalk`:

```bash
sudo -u tinitalk tinitalk init
```

The default database directory is `/var/lib/tinitalk`
(override it with `--data-dir DIR`).

The default WebPush contact is `https://tinitalk.org`
(override it with `--webpush-contact HTTPS_URL`).

The WebPush contact is an address sent to Google FCM so the TiniTalk server
owner can be contacted about push notification issues or abuse.

### 4. Configure the service

Running the TiniTalk server through systemd is recommended: it starts the
service after the VPS reboots and restarts it after a failure.

You can also run the service without systemd, for example using Docker,
supervisor, or another process manager.

To use systemd, create a unit file. Set `--turn-public-host` to the public
domain or IP address the Android client uses to connect to TURN. Set
`--turn-public-ip` to the public IPv4 address used for relay traffic.
This is usually the VPS's external IPv4 address.

In the example below, replace `calls.example.com` with the server's public
domain or IP address, and `203.0.113.10` with its public IPv4 address.

```bash
sudoedit /etc/systemd/system/tinitalk.service
```

```systemd
[Unit]
Description=TiniTalk server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=tinitalk
Group=tinitalk
WorkingDirectory=/var/lib/tinitalk
ExecStart=/usr/local/bin/tinitalk serve \
  --data-dir /var/lib/tinitalk \
  --addr :443 \
  --tls-cert /var/lib/tinitalk/tls/fullchain.pem \
  --tls-key /var/lib/tinitalk/tls/privkey.pem \
  --turn-public-host calls.example.com \
  --turn-public-ip 203.0.113.10
Restart=always
RestartSec=3
LimitNOFILE=4096
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/tinitalk
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
```

The service uses the following ports:

- HTTPS/WSS — API and signaling for the Android app (TCP).
  Set with `--addr ADDR`. Default: `:8080`.

- TURN — client connections to the built-in TURN server (UDP and TCP).
  Set with `--turn-addr ADDR`. Default: `:3478`.

- TURN/TLS — client connections to TURN over TLS (TCP).
  Set with `--turn-tls-addr ADDR`. Default: `:5349`.

- TURN relay — media traffic relayed through TURN (UDP).
  Set with `--turn-relay-min-port PORT` and `--turn-relay-max-port PORT`.
  Defaults: `49152` and `49663`, respectively (512 ports).
  `--turn-relay-max-port` must be odd.

Why reserve 512 ports for TURN relay?
By default, the service allows 128 concurrent relay allocations.
Pion selects a free relay port at random and makes at most 10 attempts.
The recommended relay port range is therefore four times the allocation limit.
With a limit of 128 allocations, a 512-port range is at most 25% occupied,
making the probability of failing to find a free port negligible.

When setting `--turn-max-allocations N`, configure a relay range of
`N × 4` ports.

### 5. Configure OS network parameters

Add the relay range to `net.ipv4.ip_local_reserved_ports` so the OS does not
assign these ports to outgoing connections from other processes. If the
parameter already contains values, append the new range with a comma,
preserving the existing entries. For the default range:

```bash
sysctl -n net.ipv4.ip_local_reserved_ports
sudoedit /etc/sysctl.d/90-tinitalk.conf
```

In `/etc/sysctl.d/90-tinitalk.conf`:

```text
net.ipv4.ip_local_reserved_ports = 49152-49663
net.core.rmem_max = 4194304
```

`rmem_max` allows a 4 MiB UDP receive buffer — an optional setting for high
loads. Do not lower an existing higher limit. To request a different buffer
size, use `--turn-udp-read-buffer BYTES` (in bytes).

```bash
sudo sysctl --load /etc/sysctl.d/90-tinitalk.conf
```

If TiniTalk is already running: `sudo systemctl restart tinitalk`.

### 6. Start the service

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now tinitalk
sudo systemctl status --no-pager tinitalk
sudo journalctl -u tinitalk -n 50 --no-pager
```

### 7. Configure the firewall

If you use a firewall, allow these incoming connections for the configuration
above:

- `443/tcp` — HTTPS/WSS.
- `3478/udp` and `3478/tcp` — TURN.
- `5349/tcp` — TURN/TLS.
- UDP ports `49152` through `49663` — TURN relay.

If you configure different ports when starting the service, allow those ports
in the firewall instead.

If the firewall restricts outbound connections, allow outbound HTTPS
connections for sending push notifications.

## Managing the TiniTalk server

Manage the server over SSH by running `tinitalk` commands on the VPS.

Commands use `/var/lib/tinitalk` by default.
To use another directory, specify `--data-dir DIR`.

### Reinitialization

You may need to run `init` again to change the WebPush contact or if the
TiniTalk server reports a missing internal TURN key:

```bash
sudo -u tinitalk tinitalk init [--data-dir DIR] [--webpush-contact HTTPS_URL]
```

This command does not replace existing keys. If `--webpush-contact` is omitted,
the current value is kept. Restart the service after changing the WebPush
contact.

### Users

```bash
sudo -u tinitalk tinitalk user add LOGIN "DISPLAY NAME"
sudo -u tinitalk tinitalk user list
sudo -u tinitalk tinitalk user rename LOGIN "DISPLAY NAME"
sudo -u tinitalk tinitalk user reset-password LOGIN
sudo -u tinitalk tinitalk user disable LOGIN
sudo -u tinitalk tinitalk user enable LOGIN
sudo -u tinitalk tinitalk user delete LOGIN
```

`DISPLAY NAME` is an internal user name visible only to the administrator.
It is not exposed through the user API or used in contact lists.

`add` and `reset-password` display an 8-digit temporary password once.
It is valid for 7 days and is locked after 5 incorrect attempts. At the first
sign-in, the user sets their own password. `reset-password` revokes previous
access; `rotate-token` remains available as an alias.

You can change the validity period for new temporary passwords without
restarting the server:
`sudo -u tinitalk tinitalk init --temporary-password-ttl 168h`.

`disable` blocks a user's access without deleting their data, `enable` restores
access, and `delete` permanently removes the user and their associated data.

To sign in for the first time on Android or the web, enter the server address,
`login`, and temporary password. Existing users remain signed in; previously
issued tokens continue to work until the password is changed or reset, or
access is revoked.

### Diagnostics

```bash
sudo -u tinitalk tinitalk doctor [--data-dir DIR] [--host HOST] [--addr ADDR] \
  [--turn-addr ADDR] [--turn-tls-addr ADDR]
```

`doctor` checks the database, internal keys, and whether local ports can be
bound. The address parameters should match those used to start the service.

- `database.integrity` should be `ok`; `fail` indicates database corruption.
  `database.foreign_keys` should be `ok`; `fail` indicates broken references
  between records.
- `database.schema` shows the schema version; `sqlite.*` shows SQLite settings.
- `users.count` shows the number of users, including disabled users.
- `turn.secret` and `webpush.vapid` show whether the internal keys are present
  and should be `ok`.
- `port.http`, `port.turn_udp`, `port.turn_tcp`, and `port.turn_tls` show
  whether local ports can be bound: `free` means binding succeeded, `busy`
  means it failed. For a running service, `busy` is expected. When the service
  is stopped, `busy` can mean another process is using the port, the address
  is unavailable, or the command lacks permission. This check does not test
  the firewall or whether the ports are reachable from outside.
- When `--host HOST` is specified without a scheme or port, `dns.HOST` shows
  the number of resolved IP addresses, and `tls.HOST` should be `ok`.
  TLS is checked on port `443`.

Read the output to assess the result: `busy`, `fail`, and `error` statuses
do not change the exit code. Always point `--data-dir` to an existing data
directory — a new path creates an empty database.

### Backups

```bash
sudo -u tinitalk tinitalk backup --out FILE [--data-dir DIR]
```

`backup` creates and verifies a consistent snapshot of the live SQLite
database, so you do not need to stop the service. If the command fails with
`database is locked`, try again later. Backups contain server secrets and must
be treated as sensitive data.

The database uses WAL mode: some recent changes may be in `state.db-wal`
rather than `state.db`. Do not manually copy just `state.db` while the server
is running. The `backup` command includes WAL data and saves a complete copy
in a single file.

```bash
sudo -u tinitalk tinitalk backup \
  --out /var/backups/tinitalk/state-$(date -u +%Y%m%dT%H%M%SZ).db
```

To restore a backup, first stop the service and check its status:

```bash
sudo systemctl stop tinitalk
sudo systemctl status --no-pager tinitalk
```

Proceed only if the status is `inactive (dead)`. If the service did not stop,
do not touch the database files. Do not run other commands that access the
database during restoration.

Remove the old WAL support files and replace the database with the backup
(replace `BACKUP_FILE` with the path to an existing backup):

```bash
sudo rm -f /var/lib/tinitalk/state.db-wal /var/lib/tinitalk/state.db-shm
sudo install -o tinitalk -g tinitalk -m 0600 \
  BACKUP_FILE \
  /var/lib/tinitalk/state.db
```

If both commands succeed, start the service:

```bash
sudo systemctl start tinitalk
```

### Pruning call history

By default, call history is retained indefinitely. The server stores only
call metadata, with no audio or video. A record, including its indexes, takes
about 200 bytes: one million calls require roughly 200 MB of disk space.

Administrators can delete records created before a specified date.
Before pruning, back up the database as described above.

Stop the service before pruning:

```bash
sudo systemctl stop tinitalk
sudo -u tinitalk tinitalk history prune --before 2025-01-01
sudo systemctl start tinitalk
```

> The date is interpreted as midnight UTC, regardless of the server's locale
> or time zone.

The command prints the number of deleted records and compacts the SQLite
database to return freed space to the operating system. Compaction requires
additional free space, so do not wait until the disk is full before pruning.

## License

TiniTalk is free and open-source software.
The BSD Zero Clause license allows you to use, copy, modify, and distribute
the project, including for commercial purposes. The software is provided
without warranties.

Full license text: [LICENSE](LICENSE).

Dependencies are distributed under their own licenses.
See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for third-party components.
