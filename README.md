# TiniTalk

[![CI](https://github.com/tinitalk/tinitalk/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/tinitalk/tinitalk/actions/workflows/ci.yml?query=branch%3Amain)
[![Release](https://img.shields.io/github/v/release/tinitalk/tinitalk?include_prereleases&sort=semver)](https://github.com/tinitalk/tinitalk/releases)

TiniTalk — self-hosted приложение для аудио- и видеозвонков один на один для
небольшой семьи или закрытой группы. Для работы нужно развернуть собственный
сервер, создать пользователей и подключить к нему Android-приложение; звонки
идут напрямую по WebRTC или через встроенный TURN.

## Сборка

Для сборки TiniTalk-сервера нужны Go 1.26.8 или новее, Git и GNU Make. Для
сборки Android-приложения дополнительно нужны JDK 17 и Android SDK Platform 37;
Gradle запускается через wrapper. JDK должен быть настроен через `JAVA_HOME`
или доступен как команда `java` через `PATH`.

### Dev-сборка

Сервер собирается командой:

```bash
make server
```

Результат: `dist/tinitalk-linux-amd64`.

Для Android доступны две dev-сборки с локальной debug-подписью:

```bash
make client
make client-min
```

- `make client` создаёт `dist/tinitalk-debug.apk` для всех поддерживаемых ABI;
- `make client-min` создаёт уменьшенный `dist/tinitalk-min.apk` только для
  ARM64 с включёнными R8 и удалением неиспользуемых ресурсов.

APK подписывается локальным debug-ключом: `~/.android/debug.keystore` на Linux
и `%USERPROFILE%\.android\debug.keystore` на Windows. Этот файл нужно сохранить:
Android не установит поверх приложения обновление, подписанное другим ключом.

Dev-сборки не требуют release-ключа и не предназначены для публикации.

### Release-сборка

Сервер собирается так же, как для dev-сборки: `make server`.

Release APK подписывается постоянным ключом проекта. Ключ нужно создать один
раз до первой публикации и затем использовать для всех следующих версий.

Создайте локальный каталог и сгенерируйте хранилище ключа:

```bash
mkdir android/keystore
keytool -genkeypair -v -keystore android/keystore/tinitalk-release.jks -alias tinitalk-release -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=TiniTalk, OU=Release Signing, O=TiniTalk Open Source Project"
```

`keytool` попросит пароль для хранилища ключа.

Создайте файл `android/keystore/release.properties`:

```properties
storeFile=keystore/tinitalk-release.jks
storePassword=ПАРОЛЬ
keyAlias=tinitalk-release
keyPassword=ПАРОЛЬ
```

Соберите релиз:

```bash
make client-release
```

Команда запускает unit-тесты, проверяет сохранение WebRTC API после R8 и
создаёт подписанный ARM64 APK. Номер версии автоматически берётся из
`versionName` в `android/app/build.gradle.kts`:

```text
dist/tinitalk-v0.10.apk
```

Каталог `android/keystore` добавлен в `.gitignore`. Файлы
`tinitalk-release.jks`, `release.properties` и пароли не должны попадать в Git.

После создания ключа обязательно сохраните резервную копию
`tinitalk-release.jks` и паролей в защищённом месте. Потеря ключа не позволит
выпускать обновления для уже установленного приложения, а утечка позволит
постороннему подписывать APK от имени проекта.

## Настройка и запуск TiniTalk-сервера

Инструкция ниже рассчитана на VPS с Debian или Ubuntu.

Потребуются готовый бинарник TiniTalk-сервера, TLS-сертификат и ключ. Сертификат
должен соответствовать домену или IP, который будет указан в
Android-приложении, и быть доверенным на устройстве. TiniTalk-сервер должен быть
доступен из интернета по публичному домену или IP. Для встроенного TURN
требуется публичный IPv4 VPS.

### 1. Создать системного пользователя и каталоги

Рекомендуется запускать TiniTalk-сервер от имени отдельного системного
пользователя `tinitalk`: так процесс не получает права root, а файлы сервера
доступны только этому пользователю.

Каталоги:

| Путь | Назначение |
|---|---|
| `/var/lib/tinitalk` | база SQLite и внутренние ключи сервера |
| `/var/lib/tinitalk/tls` | TLS-сертификат и приватный ключ |
| `/var/backups/tinitalk` | резервные копии |

```bash
sudo adduser --system --group --home /var/lib/tinitalk --no-create-home tinitalk
sudo install -d -o tinitalk -g tinitalk -m 0700 \
  /var/lib/tinitalk /var/lib/tinitalk/tls /var/backups/tinitalk
```

### 2. Скопировать бинарник и TLS-файлы

Предварительно скопировать на VPS `tinitalk-linux-amd64` и получить файлы
TLS-сертификата и приватного ключа.

Рекомендуется хранить TLS-файлы в `/var/lib/tinitalk/tls`, чтобы процесс,
запущенный от пользователя `tinitalk`, мог их читать.

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

### 3. Инициализировать хранилище

Перед первым запуском сервиса команда `tinitalk init` создаёт базу данных, схему
и внутренние ключи сервера в `/var/lib/tinitalk`:

```bash
sudo -u tinitalk tinitalk init
```

По умолчанию для создания базы данных используется каталог `/var/lib/tinitalk`
(можно переопределить параметром `--data-dir DIR`).

Значение WebPush contact по умолчанию — `https://tinitalk.org`
(можно переопределить параметром `--webpush-contact HTTPS_URL`).

WebPush contact — адрес, который передаётся Google FCM для связи с владельцем
TiniTalk-сервера при проблемах с push-уведомлениями или злоупотреблениях.

### 4. Настроить запуск сервиса

Рекомендуется запускать TiniTalk-сервер через systemd: он запускает сервис после
перезагрузки VPS и перезапускает его при сбое.

Можно запускать сервис без systemd, например через Docker, supervisor или
другой менеджер процессов.

Для настройки через systemd создать unit-файл. В `--turn-public-host` указать
публичный домен или IP, по которому Android-клиент подключается к TURN. В
`--turn-public-ip` указать публичный IPv4 для relay-трафика. Обычно это внешний
IPv4 VPS.

В примере ниже `calls.example.com` заменить публичным доменом или IP сервера, а
`203.0.113.10` — его публичным IPv4.

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

Сервис использует следующие порты:

- HTTPS/WSS — API и сигналинг для Android-приложения (протокол TCP). Задаётся
  параметром `--addr ADDR`. По умолчанию `:8080`.

- TURN — подключение клиентов к встроенному TURN (протоколы UDP и TCP). Задаётся
  параметром `--turn-addr ADDR`. По умолчанию `:3478`.

- TURN/TLS — подключение клиентов к TURN поверх TLS (протокол TCP). Задаётся
  параметром `--turn-tls-addr ADDR`. По умолчанию `:5349`.

- TURN relay — медиа-трафик через TURN (протокол UDP). Задаётся параметрами
  `--turn-relay-min-port PORT` и `--turn-relay-max-port PORT`. По умолчанию
  `49152` и `49663` соответственно (это 512 портов).
  `--turn-relay-max-port` должен быть нечётным.

Почему нужно резервировать 512 портов для TURN relay.
По умолчанию сервис имеет лимит в 128 одновременно выданных relay-адресов.
Pion выбирает свободный relay-порт случайно и делает не более 10 попыток.
Поэтому relay-диапазон рекомендуется делать в четыре раза больше лимита
allocations.
При лимите 128 диапазон из 512 портов остаётся заполнен не более чем на 25%,
и вероятность не найти свободный порт становится пренебрежимо малой.

При значении `--turn-max-allocations N` relay-диапазон необходимо задать
размером `N × 4` портов.

### 5. Зарезервировать relay-диапазон в ОС

Relay-диапазон необходимо добавить в `net.ipv4.ip_local_reserved_ports`, чтобы
ОС не назначала эти порты исходящим соединениям других процессов. Если параметр
уже содержит значения, новый диапазон добавить через запятую, не удаляя
существующие. Для диапазона по умолчанию:

```bash
sysctl -n net.ipv4.ip_local_reserved_ports
sudoedit /etc/sysctl.d/90-tinitalk.conf
```

```text
net.ipv4.ip_local_reserved_ports = 49152-49663
```

```bash
sudo sysctl --load /etc/sysctl.d/90-tinitalk.conf
```

### 6. Запустить сервис

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now tinitalk
sudo systemctl status --no-pager tinitalk
sudo journalctl -u tinitalk -n 50 --no-pager
```

### 7. Настроить firewall

Если используется firewall, для приведённой конфигурации разрешить входящие
подключения:

- `443/tcp` — HTTPS/WSS;
- `3478/udp` и `3478/tcp` — TURN;
- `5349/tcp` — TURN/TLS;
- UDP-порты с `49152` по `49663` — TURN relay.

Если при запуске сервиса заданы другие порты, разрешить их в firewall.

Если firewall ограничивает исходящие подключения, разрешить исходящие
HTTPS-соединения для отправки push-уведомлений.

## Управление TiniTalk-сервером

Управление выполняется по SSH: команды `tinitalk` запускаются на VPS.

По умолчанию команды используют каталог `/var/lib/tinitalk`. Другой каталог
указать параметром `--data-dir DIR`.

### Повторная инициализация

Повторный `init` может потребоваться для изменения WebPush contact или если
TiniTalk-сервер сообщает об отсутствии внутреннего ключа TURN:

```bash
sudo -u tinitalk tinitalk init [--data-dir DIR] [--webpush-contact HTTPS_URL]
```

Существующие ключи команда не заменяет. Если `--webpush-contact` не указан,
сохраняется текущее значение. После изменения WebPush contact перезапустить
сервис.

### Пользователи

```bash
sudo -u tinitalk tinitalk user add LOGIN "DISPLAY NAME"
sudo -u tinitalk tinitalk user list
sudo -u tinitalk tinitalk user rename LOGIN "DISPLAY NAME"
sudo -u tinitalk tinitalk user rotate-token LOGIN
sudo -u tinitalk tinitalk user disable LOGIN
sudo -u tinitalk tinitalk user enable LOGIN
sudo -u tinitalk tinitalk user delete LOGIN
```

`add` и `rotate-token` показывают новый token только один раз. `rotate-token`
сбрасывает регистрации устройств и push-подписки.

`disable` блокирует доступ пользователя без удаления данных, `enable` возвращает
доступ, а `delete` необратимо удаляет пользователя и связанные с ним данные.

Для входа в Android-приложение указать адрес TiniTalk-сервера, `login` и
`token`, выданные командой `user add`.

### Диагностика

```bash
sudo -u tinitalk tinitalk doctor [--data-dir DIR] [--host HOST] [--addr ADDR] \
  [--turn-addr ADDR] [--turn-tls-addr ADDR]
```

`doctor` проверяет базу, внутренние ключи и возможность занять локальные порты.
Параметры адресов должны совпадать с параметрами запуска сервиса.

- `database.integrity` должно иметь значение `ok`; `fail` означает повреждение
  базы. `database.foreign_keys` должно иметь значение `ok`; `fail` означает
  нарушение связей между данными.
- `database.schema` показывает версию схемы, `sqlite.*` — параметры SQLite.
- `users.count` показывает количество пользователей, включая отключённых.
- `turn.secret` и `webpush.vapid` показывают наличие внутренних ключей и должны
  иметь значение `ok`.
- `port.http`, `port.turn_udp`, `port.turn_tcp` и `port.turn_tls` показывают
  возможность занять локальные порты: `free` — порт удалось занять, `busy` — не
  удалось. Для запущенного сервиса ожидается `busy`. Для остановленного `busy`
  может означать, что порт занят другим процессом, адрес недоступен или у
  команды недостаточно прав. Эта проверка не проверяет firewall и доступность
  портов извне.
- При указании `--host HOST` без схемы и порта строка `dns.HOST` показывает
  количество найденных IP-адресов, а `tls.HOST` должна иметь значение `ok`. TLS
  проверяется на порту `443`.

Результат определять по выводу: статусы `busy`, `fail` и `error` не меняют exit
code. Для `--data-dir` всегда указывать существующий каталог данных — новый путь
создаст пустую базу.

### Резервное копирование

```bash
sudo -u tinitalk tinitalk backup --out FILE [--data-dir DIR]
```

`backup` создаёт и проверяет согласованный снимок работающей базы SQLite,
поэтому останавливать сервис не требуется. Если команда завершилась с ошибкой
`database is locked`, повторить её позднее. Резервная копия содержит секреты
сервера и должна храниться как чувствительные данные.

```bash
sudo -u tinitalk tinitalk backup \
  --out /var/backups/tinitalk/state-$(date -u +%Y%m%dT%H%M%SZ).db
```

Для восстановления остановить сервис и заменить `state.db` файлом резервной
копии:

```bash
sudo systemctl stop tinitalk
sudo install -o tinitalk -g tinitalk -m 0600 \
  BACKUP_FILE \
  /var/lib/tinitalk/state.db
sudo systemctl start tinitalk
```

### Очистка истории звонков

По умолчанию история звонков хранится без ограничения срока. Сервер сохраняет
только метаданные звонков, без аудио и видео. Одна запись вместе с индексами
занимает примерно 200 байт: один миллион звонков потребует около 200 МБ диска.

При необходимости администратор может удалить записи, созданные до указанной
даты. Перед очисткой рекомендуется сделать резервную копию, как описано выше.

На время очистки сервис нужно остановить:

```bash
sudo systemctl stop tinitalk
sudo -u tinitalk tinitalk history prune --before 2025-01-01
sudo systemctl start tinitalk
```

> Дата интерпретируется как полночь по UTC и не зависит от локали или часового
> пояса сервера.

Команда выводит количество удалённых записей и уплотняет базу SQLite, чтобы
освободившееся место вернулось операционной системе. Для уплотнения требуется
дополнительное свободное место, поэтому очистку нельзя откладывать до полного
заполнения диска.

## Лицензия

TiniTalk — бесплатное программное обеспечение с открытым исходным кодом.
Лицензия BSD Zero Clause разрешает использовать, копировать, изменять и
распространять проект, в том числе в коммерческих целях. Программное обеспечение
предоставляется без гарантий.

Полный текст лицензии: [LICENSE](LICENSE).

Зависимости распространяются под собственными лицензиями.
Список сторонних компонентов: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
