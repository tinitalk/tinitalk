# TiniTalk

Self-hosted Android-аудиозвонки для небольшой семьи. Сервер - один Go-бинарник:
HTTPS/WSS-сигналинг, SQLite-состояние, FCM-пробуждение входящих звонков и
встроенный TURN fallback.

## Быстрый старт

Пять основных команд:

```bash
make server
sudo install -m 0755 dist/tinitalk-linux-amd64 /usr/local/bin/tinitalk
sudo -u tinitalk tinitalk init --fcm-service-account firebase-service-account.json
sudo -u tinitalk tinitalk user add alice "Alice"
make client
```

Все команды по умолчанию используют `/var/lib/tinitalk`. Для другого пути
добавь `--data-dir DIR` в любое место после команды.

### Управление пользователями

```bash
sudo -u tinitalk tinitalk user add alice "Alice"
sudo -u tinitalk tinitalk user list
sudo -u tinitalk tinitalk user rotate-token alice
sudo -u tinitalk tinitalk user disable alice
sudo -u tinitalk tinitalk user delete alice
```

`disable` блокирует вход, но сохраняет пользователя. `delete` физически удаляет
пользователя, его токены и зарегистрированные устройства. Если удаляемый
пользователь уже подключен, перезапусти сервер для немедленного разрыва WSS.

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
make client-min
```

`make client` быстро собирает универсальный debug APK в
`dist/tinitalk-debug.apk`. `make client-min` собирает оптимизированный ARM64 APK
в `dist/tinitalk-min.apk`; он подходит для телефонов и планшетов Android с
64-битной ARM-архитектурой (`arm64-v8a`), то есть для большинства современных
Android-устройств. Если архитектура неизвестна, используй универсальный debug APK.

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
tinitalk serve --addr :443 \
  --tls-cert /var/lib/tinitalk/tls/fullchain.pem \
  --tls-key /var/lib/tinitalk/tls/privkey.pem \
  --turn-public-host calls.example.com \
  --turn-public-ip 203.0.113.10 \
  --turn-addr :3478 \
  --turn-tls-addr :5349
```

Встроенный TURN по умолчанию допускает `128` одновременных allocations,
не более `8` на пользователя, и выделяет relay-порты из UDP-диапазона
`49152-49663`. Повышенный per-user лимит оставляет место для одновременных
UDP/TCP/TLS allocations старой и новой сети во время handover. Реальное число
одновременных звонков зависит от маршрутов и ресурсов VPS, поэтому его нужно
подтверждать нагрузочным тестом. Прямые peer-to-peer звонки этот лимит не
расходуют. Временные TURN credentials действуют `10` минут; Android на стороне
offerer за минуту до истечения срока инициирует ICE restart, после чего оба
участника получают свежую конфигурацию.

Лимит и relay-диапазон можно переопределить без пересборки:

```bash
tinitalk serve ... \
  --turn-max-allocations 64 \
  --turn-max-allocations-per-user 8 \
  --turn-relay-min-port 50000 \
  --turn-relay-max-port 50255
```

Relay-диапазон должен содержать минимум четыре порта на каждый разрешенный
allocation. `--turn-max-allocations-per-user` не должен превышать общий лимит.
Если диапазон переопределен, тот же диапазон нужно открыть в firewall/security
group и добавить в `net.ipv4.ip_local_reserved_ports`.

Для запуска через systemd замени example hostname и IP в
`deploy/tinitalk.service`, затем установи unit:

```bash
sudo install -m 0644 deploy/tinitalk.service /etc/systemd/system/tinitalk.service
sudo systemctl daemon-reload
sudo systemctl enable --now tinitalk
```

## Диагностика и backup

```bash
tinitalk doctor --host calls.example.com --addr :443 --turn-addr :3478 --turn-tls-addr :5349
tinitalk backup --out /var/backups/tinitalk/state-$(date +%F).db
make check
```

## Заметки по VPS

- DNS `A` record должен указывать на VPS до выпуска сертификата.
- В firewall и security group открой `443/tcp`, `3478/udp`, `3478/tcp`,
  `5349/tcp` и UDP relay-диапазон `49152-49663`. TCP/TLS transport подключается
  к TURN через `3478`/`5349`; WebRTC media relay использует UDP-диапазон.
- На Linux добавь `49152-49663` в `net.ipv4.ip_local_reserved_ports`, сохранив
  уже настроенные reserved ranges, чтобы исходящие соединения ОС не занимали
  TURN relay-порты.
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

- Сервер и Android-приложение используют строгую версию signaling protocol
  `v2`. При обновлении сервера пересобери и установи новый APK на все телефоны:
  старые версии приложения подключаться не будут.

- Перед `make client` или `make client-min` положи Firebase Android config в
  `android/app/google-services.json`.
- `make client` быстро собирает универсальный debug APK
  `dist/tinitalk-debug.apk` со всеми поддерживаемыми ABI.
- `make client-min` собирает оптимизированный ARM64 release APK
  `dist/tinitalk-min.apk` и проверяет, что R8 не удалил JNI API WebRTC.
- Оба APK подписываются локальным Android debug-ключом. Для обновления без
  удаления приложения собирай их на том же компьютере и сохрани
  `%USERPROFILE%\.android\debug.keystore` (на Linux это
  `~/.android/debug.keystore`).
- По умолчанию экран входа использует `https://tinitalk.example.com`. Другой
  адрес можно зашить в APK при сборке:

```bash
make client SERVER_URL=https://talk.example.com
```

  При прямом запуске Gradle используй
  `-PtinitalkServerUrl=https://talk.example.com` и `-PtinitalkAbi=arm64` либо
  `-PtinitalkAbi=all`. Адрес по-прежнему можно изменить вручную в настройках
  сервера на экране входа.
- Установи `dist/tinitalk-min.apk` на ARM64-телефон либо
  `dist/tinitalk-debug.apk`, если архитектура неизвестна. Открой приложение
  один раз, войди и выдай
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
