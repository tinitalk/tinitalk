# TiniTalk

Self-hosted Android-аудиозвонки для небольшой семьи. Сервер - один Go-бинарник:
HTTPS/WSS-сигналинг, SQLite-состояние, FCM-пробуждение входящих звонков и
встроенный TURN fallback.

## Быстрый старт

Пять основных команд:

```bash
make server
sudo install -m 0755 dist/tinitalk-linux-amd64 /usr/local/bin/tinitalk
sudo -u tinitalk tinitalk init --data-dir /var/lib/tinitalk --fcm-service-account firebase-service-account.json
sudo -u tinitalk tinitalk user add --data-dir /var/lib/tinitalk alice "Alice"
make client
```

Те же Make target'ы работают из Windows и WSL. В WSL сервер собирается Linux
Go toolchain'ом, а Android-сборка использует Windows JDK и Android SDK через
WSL interop. Если JDK или `cmd.exe` лежат нестандартно, переопредели `JAVA17`
или `WINDOWS_CMD`. На обычном Linux Gradle использует `JAVA_HOME` и Android SDK
из окружения.

## Firebase-файлы

Для полноценного FCM нужны два разных JSON-файла из одного Firebase project:

- `google-services.json` - клиентский Android-конфиг для APK.
- `firebase-service-account.json` - серверный ключ service account для отправки
  FCM push через HTTP v1 API.

Это разные файлы. `google-services.json` не содержит `private_key` и не подходит
для сервера. Service account JSON содержит `type: "service_account"`,
`project_id`, `client_email` и `private_key`.

### Как открыть Firebase Console

1. Открой в браузере:

```text
https://console.firebase.google.com/
```

2. Войди в Google-аккаунт.
3. Если Firebase project для TiniTalk уже есть, кликни по нему.
4. Если проекта еще нет, нажми `Create a project` / `Создать проект` и пройди
   мастер создания. Google Analytics можно включить или пропустить - для
   TiniTalk это не принципиально.
5. Внутри проекта нажми шестеренку рядом с `Project Overview`.
6. Выбери `Project settings`.

Дальше в `Project settings` есть две нужные вкладки:

```text
General          -> для google-services.json
Service accounts -> для firebase-service-account.json
```

### Android google-services.json

Нужен, чтобы Android-приложение могло получить FCM registration token.

Как получить:

1. Открой `Project settings` -> `General`.
2. В блоке `Your apps` нажми Android-иконку или выбери уже созданное Android
   app.
3. Если добавляешь новое Android app, Android package name должен быть:

```text
org.tinitalk
```

4. Нажми `Register app`.
5. Скачай `google-services.json`.
6. Положи файл сюда:

```text
android/app/google-services.json
```

После этого собирай APK:

```bash
make client
```

Файл `android/app/google-services.json` игнорируется Git'ом. APK без него
соберется, но FCM-пробуждение из фона работать не будет.

### Server firebase-service-account.json

Нужен серверу, чтобы отправлять FCM push при входящем звонке.

Как получить:

1. Открой тот же Firebase project.
2. Открой `Project settings` -> `Service accounts`.
3. Нажми `Generate new private key`.
4. Подтверди `Generate key`.
5. Сохрани скачанный JSON как, например:

```text
firebase-service-account.json
```

Не коммить этот файл. В нем есть приватный ключ.

На сервере передай этот файл при первой инициализации:

```bash
sudo -u tinitalk tinitalk init \
  --data-dir /var/lib/tinitalk \
  --fcm-service-account firebase-service-account.json
```

`tinitalk init` сохраняет содержимое service account в SQLite state, поэтому
сам JSON-файл после успешной инициализации можно удалить с сервера.

Если запустить `tinitalk init` без `--fcm-service-account`, звонки будут
работать только когда приложение уже активно или само подключено к серверу;
FCM-пробуждение телефона из фона будет недоступно.

## Сертификат

Получи сертификат через Certbot и скопируй актуальную пару туда, где сервис
`tinitalk` сможет ее читать:

```bash
sudo certbot certonly --standalone -d calls.example.com
sudo install -d -o tinitalk -g tinitalk -m 0700 /var/lib/tinitalk/tls
sudo install -o tinitalk -g tinitalk -m 0644 /etc/letsencrypt/live/calls.example.com/fullchain.pem /var/lib/tinitalk/tls/fullchain.pem
sudo install -o tinitalk -g tinitalk -m 0600 /etc/letsencrypt/live/calls.example.com/privkey.pem /var/lib/tinitalk/tls/privkey.pem
```

Эти же две команды `install` стоит добавить в Certbot deploy hook после
renewal. TiniTalk читает TLS-файлы на каждом новом TLS-соединении и начинает
использовать обновленную пару без рестарта. Если renewal временно откроет
неполную пару, последняя валидная пара останется активной.

## Запуск сервера

```bash
tinitalk serve --data-dir /var/lib/tinitalk --addr :443 \
  --tls-cert /var/lib/tinitalk/tls/fullchain.pem \
  --tls-key /var/lib/tinitalk/tls/privkey.pem \
  --turn-public-host calls.example.com \
  --turn-public-ip 203.0.113.10 \
  --turn-addr :3478 \
  --turn-tls-addr :5349
```

Для запуска через systemd замени example hostname и IP в
`deploy/tinitalk.service`, затем установи unit:

```bash
sudo install -m 0644 deploy/tinitalk.service /etc/systemd/system/tinitalk.service
sudo systemctl daemon-reload
sudo systemctl enable --now tinitalk
```

## Диагностика и backup

```bash
tinitalk doctor --data-dir /var/lib/tinitalk --host calls.example.com --addr :443 --turn-addr :3478 --turn-tls-addr :5349
tinitalk backup --data-dir /var/lib/tinitalk --out /var/backups/tinitalk/state-$(date +%F).db
make check
```

## Заметки по VPS

- DNS `A` record должен указывать на VPS до выпуска сертификата.
- `/var/lib/tinitalk/state.db` должен принадлежать `tinitalk:tinitalk` и иметь
  mode `0600`.
- Не коммить Firebase service account JSON, `state.db`, APK и собранные
  бинарники.
- Для обновления: останови сервис, замени `/usr/local/bin/tinitalk`, запусти
  `doctor` от root пока низкие порты свободны, затем снова запусти сервис.
- Для восстановления: останови сервис, скопируй проверенный backup в
  `/var/lib/tinitalk/state.db`, поправь owner/mode, запусти сервис и выполни
  `doctor`.

## Заметки по Android

- Перед `make client` положи Firebase Android config в
  `android/app/google-services.json`.
- Установи `dist/tinitalk-debug.apk`, открой приложение один раз, войди и выдай
  разрешения на микрофон, уведомления и full-screen incoming calls.
- Relay-only диагностический APK собирается так:

```bash
make client GRADLE_ARGS=-PtinitalkForceRelay=true
```

После диагностики пересобери обычный APK.

- Проверь и прямой media path, и принудительный TURN relay из реальных сетей,
  которые важны.
- Во время активного звонка смотри redacted WebRTC diagnostics:

```bash
adb logcat -s TiniTalkCall
```

- Forced-relay прогон считается успешным только если
  `local_candidate_type` или `remote_candidate_type` равен `relay`; эти логи не
  содержат IP-адреса или credentials.
