# myVPNproject v3.7 CIS-MAX — Android DPI Bypass

Android-приложение для обхода DPI-блокировок без root и без внешнего сервера.  
Трафик перехватывается локально через TUN-интерфейс, форвардируется полноценным
нативным tun2socks (badvpn/lwIP) в SOCKS5, где нативный движок ciadpi (byedpi)
делает DPI-обход (TCP split/disorder/fake).

Вдохновлено проектами
[ByeDPI (ciadpi)](https://github.com/hufrea/byedpi),
[ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid),
[badvpn/tun2socks](https://github.com/ambrop72/badvpn) и
[Zapret](https://github.com/bol-van/zapret).

---

## Архитектура v3.7 CIS-MAX

```
Браузер/приложение
       ↓
   TUN-интерфейс (10.0.0.2/24)    ←— BypassVpnService.kt
       ↓
  tun2socks  (badvpn + lwIP, C, JNI)     ←— tun2socks_jni.so
  Читает/пишет TUN fd НАПРЯМУЮ в нативном коде (dup()'нутый fd),
  полноценный TCP/IP стек lwIP терминирует TCP-соединения,
  UDP (QUIC/DNS) идёт через SOCKS5 UDP ASSOCIATE
       ↓
   SOCKS5  127.0.0.1:1080
       ↓
  ciadpi  (byedpi, C, JNI)              ←— ciadpi_jni.so
  TCP split / disorder / fake / OOB / TLS record split
       ↓
     Интернет
```

**Важно (история v3.6→v3.7):** до этой версии `PacketProcessor.kt` фрагментировал
TLS ClientHello и писал оба фрагмента **обратно в тот же TUN fd** — но у Android
TUN-интерфейса нет автоматической маршрутизации/NAT наружу: всё, что пишется
обратно в TUN, интерпретируется системой как **входящий** трафик для этого же
интерфейса, а не отправляется на реальный сервер. Реальная доставка пакетов в
интернет требует либо отдельного `protect()`-нутого сокета на каждое TCP/UDP
соединение, либо полноценного userspace TCP/IP стека поверх такого сокета —
именно эту роль и выполняет tun2socks (lwIP). Начиная с v3.7 это реализовано:
`tun2socks_bridge_run()` (патч в `tun2socks.c`) принимает уже открытый TUN fd
через `BTAP_INIT_FD` (не требует root) и полностью форвардит TCP через lwIP →
`BSocksClient` → SOCKS5, а UDP — через `SocksUdpClient` UDP ASSOCIATE.
Путь TUN → lwIP → SOCKS5 → обратно протестирован E2E на Linux (реальный TUN +
тестовый SOCKS5-сервер) в рамках разработки.

Пока native tun2socks активен, Kotlin **не читает TUN fd** — старый
`PacketProcessor`-цикл остаётся только как fallback для stub-сборки без badvpn
(см. `ProxyEngine.isNativeTun2socksBuilt()`).

### Компоненты

| Компонент | Роль |
|---|---|
| `BypassVpnService.kt` | TUN-интерфейс, foreground service, передаёт `tunFd` в `ProxyEngine`; выбирает native/fallback путь |
| `ProxyEngine.kt` | Kotlin JNI-мост: запуск ciadpi → tun2socks, `isNativeTun2socksBuilt()` |
| `ciadpi_jni.c` | C JNI-мост для byedpi (реальный, vendored, не submodule) |
| `tun2socks_jni.c` | C JNI-мост для badvpn/tun2socks (реальный, vendored) |
| `badvpn/tun2socks/tun2socks_bridge.h` | Библиотечный API поверх апстримного tun2socks.c (принимает открытый fd вместо CLI) |
| `ConfigManager.kt` | Пресеты (включая СНГ: kz-telecom/mts-ru/beeline-ru/rostelecom), JSON-сериализация конфигурации |
| `PacketProcessor.kt` | Fallback-путь (stub-сборка без badvpn): TCP split TLS ClientHello (IPv4 + IPv6) |
| `DnsInterceptor.kt` | Перехват UDP:53 на уровне TUN → резолв через `DohResolver` → синтез DNS-ответа |
| `DohResolver.kt` | DoH-клиент на чистом `HttpsURLConnection`/`org.json` (без внешних зависимостей) |
| `NetworkProfileDetector.kt` | Авто-определение оператора СНГ по ASN (ip-api.com через `protect()`-сокет) |
| `ChecksumUtils.kt` | IPv4/IPv6 IP/TCP/UDP чек-суммы, общие для PacketProcessor и DnsInterceptor |
| `MainActivity.kt` | UI: Jetpack Compose — пресеты, приложения, справка |

---

## Пресеты

| ID | Название | Когда использовать |
|---|---|---|
| `universal` | Универсальный | По умолчанию, большинство блокировок |
| `youtube` | YouTube (агрессивный) | YouTube заблокирован |
| `telegram` | Telegram | Блокировки Telegram |
| `minimal` | Минимальный | Экономия батареи |
| `aggressive` | Максимальный | Ничего другого не помогло |
| `kz-telecom` | Казахтелеком / Kcell (KZ) | Казахстанские операторы |
| `mts-ru` | МТС (РФ) | Блокировки под МТС |
| `beeline-ru` | Билайн (РФ) | Блокировки под Билайн |
| `rostelecom` | Ростелеком (РФ) | Блокировки под ТСПУ Ростелекома |

---

## Сборка

byedpi и badvpn/tun2socks/lwIP **вендорены прямо в репозиторий** (не submodule) —
достаточно обычного клона:

```bash
git clone https://github.com/MaksKbz/myVPNproject.git
cd myVPNproject
./gradlew assembleDebug
```

Собранный APK содержит реальный DPI-обход (ciadpi) и реальный TUN→SOCKS5
форвардинг (tun2socks) — не stub. Если badvpn-исходники по какой-то причине
отсутствуют в дереве (`app/src/main/jni/badvpn/tun2socks/tun2socks.c` не
найден), CMake автоматически переключается в safety-fallback: `tun2socks_jni.so`
собирается без реального badvpn, а `BypassVpnService` использует
Kotlin `PacketProcessor`-цикл как fallback (см. предупреждение выше про его
ограничения).

### Требования

- Android Studio Hedgehog+
- JDK 17+
- Android SDK API 24+
- **NDK 27.0.12077973** (устанавливается автоматически через CI или `sdkmanager`)

---

## CI / GitHub Actions

Автоматическая сборка Debug APK при каждом push в `master`.  
Степы: checkout → JDK 17 → NDK 27 → Gradle → assembleDebug → upload artifact.

Artifact доступен в разделе [Actions](https://github.com/MaksKbz/myVPNproject/actions).

---

## Дорожная карта v3.7 CIS-MAX

- [x] Kotlin userspace TCP split (v3.2.0, теперь fallback-путь)
- [x] byedpi vendored (не submodule) — ciadpi_jni.c реально вызывает `run()` из byedpi/proxy.c
- [x] IPv6 TCP split — полностью реализован (ChecksumUtils, PacketProcessor, юнит-тесты)
- [x] DoH-клиент — перехват UDP:53 на уровне TUN + резолв через DoH (DnsInterceptor.kt)
- [x] Пресеты СНГ: `kz-telecom`, `mts-ru`, `beeline-ru`, `rostelecom` + авто-определение
      оператора по ASN (NetworkProfileDetector.kt, ip-api.com через protect()-сокет)
- [x] **tun2socks (badvpn) — реализован и E2E-протестирован.** Патч `TUN2SOCKS_LIBRARY_MODE`
      в `tun2socks.c` добавляет `tun2socks_bridge_run()/stop()`, принимающие уже
      открытый TUN fd через `BTAP_INIT_FD` (root не требуется). Полноценный lwIP
      TCP/IP стек + SOCKS5 UDP ASSOCIATE для UDP (QUIC/DNS).
- [x] **IPv6 destinations в ciadpi** — `params.baddr` переведён на dual-stack
      (`AF_INET6` + `in6addr_any`, `IPV6_V6ONLY=0` для сокетов назначения
      AF_INET6), `params.ipv6 = true`. Раньше `baddr` был чистым `AF_INET`,
      и `remote_sock()`/`map_fix()` отклоняли любое подключение к IPv6-адресу
      назначения (`S_ATP_I6` от tun2socks). Функционально проверено локально:
      dual-stack сокет успешно подключается и к v4-mapped-v6, и к нативным
      IPv6-адресам одновременно.
- [x] **Фикс краша при рестарте tun2socks (BNetwork/BTime one-shot ASSERT)** —
      найден по факту реального теста на устройстве (VPN включался,
      уведомление появлялось, но само приложение мгновенно "сворачивалось",
      а VPN не работал). Причина: `BNetwork_GlobalInit()` и `BTime_Init()`
      в badvpn защищены апстримными `ASSERT(!initialized)` — при **первом**
      запуске `tun2socks_bridge_run()` всё в порядке, но эти функции
      вызываются заново при **каждом** перезапуске движка
      (`ASN auto-detect` — срабатывает через 1.5с после старта VPN,
      `preset auto-switch`, ручной рестарт), а глобальные one-shot флаги
      никогда не сбрасываются обратно в 0. Второй вызов → `ASSERT` →
      `abort()` → `SIGABRT` убивает **весь процесс приложения** мгновенно,
      минуя все Kotlin `try/catch`. Исправлено: обе функции сделаны
      идемпотентными. Воспроизведено и подтверждено локальным
      C-репродюсером (5 циклов restart подряд, `EXIT_CODE=0`, без единого
      краша, после фикса).
      **ВАЖНО:** после этого фикса пользователь сообщил, что краш при
      нажатии "Запустить VPN" **всё ещё происходит** (уже после появления
      уведомления, то есть до истечения 1.5с ASN auto-detect) — значит
      есть ещё как минимум одна причина, не покрытая этим фиксом, и не
      воспроизведённая пока на Linux x86_64 (либо специфична для реального
      устройства/ABI). См. следующий пункт.
- [x] **CrashLogger — сборщик диагностики крашей без adb** — раз
      пользователь тестирует на устройстве без доступа к `adb logcat`,
      добавлен `CrashLogger.kt` (Kotlin `UncaughtExceptionHandler`) +
      `crash_handler.c/.h` (нативный обработчик `SIGABRT`/`SIGSEGV`/
      `SIGBUS`/`SIGILL`/`SIGFPE`/`SIGTRAP`, пишет async-signal-safe через
      `write()` без malloc/printf внутри самого обработчика — проверено
      функциональными тестами с намеренным `abort()`/null-deref). Оба лога
      сохраняются в `filesDir` приложения и **показываются прямо в UI**
      при следующем запуске (карточка вверху экрана с кнопками
      "Копировать"/"Закрыть") — не требует ADB. Также расставлены
      "контрольные точки" (`crash_log_checkpoint()`) перед каждым опасным
      нативным вызовом (`ciadpiStart`→`run()`, `tun2socksStart`→
      `tun2socks_bridge_run()`), чтобы даже если сигнальный обработчик не
      успеет сработать, по последней записанной точке было понятно, где
      именно произошёл сбой.
      **ВАЖНО:** пользователь протестировал v3.7.3 — в логе была ТОЛЬКО
      строка "crash handler installed" (пишется при запуске приложения),
      после неё — ничего, ни блока `NATIVE CRASH`, ни моих чекпоинтов. Это
      означало, что сам CrashLogger не сработал корректно. См. следующий
      пункт — найдены и исправлены 2 реальных бага в самом CrashLogger.
- [x] **Фикс CrashLogger (v3.7.4)** — по факту пустого лога найдены 2 бага
      в диагностическом коде из v3.7.3: (1) `crash_handler.c` компилируется
      ОТДЕЛЬНО в каждую `.so` (`ciadpi_jni.so`/`tun2socks_jni.so`) — это
      два независимых набора глобальных переменных, а `installCrashHandler()`
      вызывался только со стороны `ciadpi_jni.so`, поэтому все чекпоинты в
      `tun2socks_jni.so` молча писали в никуда (`g_log_fd` там навсегда
      оставался `-1`); теперь обработчик устанавливается явно в ОБЕИХ
      библиотеках. (2) `SA_ONSTACK` был указан в `sa_flags`, но реальный
      альтернативный стек через `sigaltstack()` никогда не выделялся — при
      краше из-за переполнения стека (частый сценарий в глубоко вложенном
      C-коде вроде lwIP) обработчик пытался выполниться на уже испорченном
      стеке и сам немедленно погибал молча, не успев ничего записать —
      это отлично объясняло полностью пустой лог у пользователя. Оба бага
      подтверждены контрольным экспериментом: тестовая программа с
      намеренным переполнением стека в отдельном pthread БЕЗ фикса даёт
      абсолютно пустой лог (`SIGSEGV`, `exit 139`, но ни строки
      диагностики), С фиксом — тот же краш корректно перехвачен и
      записан (`Signal: SIGSEGV (11)`, `faulting address`, и т.д.).
- [ ] ECH (Encrypted Client Hello) — требует сборки BoringSSL с ECH под NDK
      и локального TLS-терминирующего прокси. См. [`TUN2SOCKS_AND_ECH_PLAN.md`](./TUN2SOCKS_AND_ECH_PLAN.md).
- [ ] Полное E2E-тестирование на реальном устройстве (Алматы / РФ) — ждём
      от пользователя текста из карточки краша (после установки версии
      v3.7.4 с исправленным CrashLogger) для точной диагностики оставшейся
      причины; тестовая сборка проверена на Linux x86_64


---

## Ссылки

- [ByeDPI (ciadpi)](https://github.com/hufrea/byedpi) — референсный C-движок DPI-обхода
- [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) — Android-реализация ciadpi
- [badvpn/tun2socks](https://github.com/ambrop72/badvpn) — TUN → SOCKS5 прокси
- [Zapret](https://github.com/bol-van/zapret) — альтернативный DPI-обход
- [NATIVE_ENGINE_SETUP.md](./NATIVE_ENGINE_SETUP.md) — историческая инструкция активации (устарела с v3.7 — сборка теперь не требует ручных шагов)
