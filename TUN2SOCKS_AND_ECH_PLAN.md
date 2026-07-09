# tun2socks (badvpn) — реализовано в v3.7. ECH — план на будущее

## 1. tun2socks (badvpn) — ✅ РЕАЛИЗОВАНО в v3.7 CIS-MAX

### 1.1 Что оказалось не так в предыдущем плане

Более ранняя версия этого документа утверждала, что нужен патч `--tunfd` в
badvpn (беря пример с ByeDPIAndroid/hev-socks5-tunnel). **Это было неточно.**
При реальном изучении исходников оказалось, что `badvpn/tuntap/BTap.h` уже
содержит нужный механизм "из коробки":

```c
enum BTap_init_type {
    BTAP_INIT_STRING,  // открыть /dev/net/tun по имени устройства (root)
#ifndef BADVPN_USE_WINAPI
    BTAP_INIT_FD,      // ← принять уже открытый fd напрямую!
#endif
};
```

`BTap_Init2()` с `BTAP_INIT_FD` — ровно то, что нужно: передать
`ParcelFileDescriptor.getFd()` из `VpnService.Builder.establish()` без
всякого патчинга. Отдельный патч `--tunfd` не понадобился.

### 1.2 Что было реально сделано

1. **Vendored badvpn** (не submodule, как и byedpi) в
   `app/src/main/jni/badvpn/` — обрезано с 12MB до ~4.6MB (убраны
   client/server/ncd/examples/dostest/win32-специфика, не нужные для
   tun2socks; список файлов взят из `compile-tun2socks.sh` — официального
   "быстрого Linux-билда" от автора badvpn).

2. **Патч `tun2socks.c`**: добавлен библиотечный режим
   `TUN2SOCKS_LIBRARY_MODE`, который:
   - оборачивает оригинальный CLI `int main(argc, argv)` в
     `#ifndef TUN2SOCKS_LIBRARY_MODE` (апстримный код НЕ тронут — сохраняет
     совместимость с будущими апдейтами badvpn);
   - добавляет `tun2socks_bridge_run(config)` / `tun2socks_bridge_stop()`
     (см. `tun2socks_bridge.h`) — переиспользует существующие
     `process_arguments()`, `terminate()` и весь event-handling код,
     заполняя `options.*` программно вместо `parse_arguments(argc, argv)`;
   - TUN открывается через `BTAP_INIT_FD` с `dup()`'нутым fd (оригинал,
     переданный из Kotlin, остаётся у `ParcelFileDescriptor` и закрывается
     Android-стороной как обычно);
   - остановка — через self-pipe, зарегистрированный в `BReactor` как
     обычный файловый дескриптор (тот же паттерн, что `BADVPN_USE_SELFPIPE`
     в `BUnixSignal.c`) — `BReactor` не защищён мьютексами (`NO_SYS=1`
     дизайн lwIP), поэтому вызвать `BReactor_Quit()` напрямую из другого
     потока небезопасно;
   - логирование через инжектируемый `Tun2SocksBridgeLogFunc`
     (`__android_log_print` на Android-стороне) вместо `BLog_InitStdout()`.

3. **`tun2socks_jni.c`** переписан: вместо пустого цикла `while(g_running)`
   вызывает `tun2socks_bridge_run()` в pthread, с fallback на прежнюю
   заглушку, если `BADVPN_AVAILABLE` не определён на этапе сборки.

4. **`CMakeLists.txt`**: `badvpn_tun2socks` собирается как статическая
   библиотека из ~50 .c-файлов (badvpn core + lwIP core, без api/apps/netif —
   не нужны при `NO_SYS=1`), с теми же флагами, что `compile-tun2socks.sh`
   (`BADVPN_USE_EPOLL`, `BADVPN_USE_SIGNALFD` — стандартные Linux/Bionic API,
   доступны на Android без изменений).

5. **`ciadpi_jni.c`**: включён `params.udp = true` — иначе SOCKS5 UDP
   ASSOCIATE (нужен для QUIC/DNS через tun2socks → `SocksUdpClient`)
   отклонялся бы byedpi на уровне `handle_s5()`.

### 1.3 Проверка

Полный путь **TUN → lwIP → BSocksClient → SOCKS5 → обратно** был
E2E-протестирован на этой машине (Linux x86_64):
1. Собран весь badvpn+lwIP набор + пропатченный `tun2socks.c` в
   `TUN2SOCKS_LIBRARY_MODE` — компилируется чисто, без предупреждений.
2. Создан реальный `/dev/net/tun`-интерфейс (`sudo`, т.к. локально нет
   Android-эмулятора), запущен `tun2socks_bridge_run()` в pthread.
3. Запущен минимальный Python SOCKS5-сервер (echo).
4. `nc 10.0.0.2 12345` отправил данные через TUN — фейковый SOCKS5-сервер
   получил `CONNECT` запрос и данные, эхо вернулось клиенту через тот же
   TUN. Оба направления (TUN→SOCKS5 и SOCKS5→TUN) подтверждены рабочими.
5. `tun2socks_bridge_stop()` корректно останавливает event loop без
   segfault/утечек (после исправления найденного и устранённого бага —
   `BLog_Free()` вызывал `free_func()` безусловно, для кастомного
   Android-логгера была нужна no-op-функция, а не `NULL`).

**Не проверено:** реальная сборка через Android NDK (недоступен в этой
рабочей среде — нет сети для скачивания NDK) и запуск на настоящем
Android-устройстве/эмуляторе. Компиляция под Bionic должна работать
идентично Linux x86_64 (те же `BADVPN_LINUX`/`BADVPN_USE_EPOLL` флаги,
никаких glibc-специфичных вызовов в задействованных файлах не найдено), но
это стоит подтвердить прогоном CI (`./gradlew assembleDebug`) и ручным
тестом на устройстве перед тем, как полагаться на этот путь в проде.

### 1.4 Известные ограничения после этой реализации

- ~~**IPv6 в ciadpi**: `params.ipv6 = false`~~ — **исправлено**. `ciadpi_jni.c`
  теперь настраивает `params.baddr` как dual-stack (`AF_INET6` +
  `in6addr_any`), `params.ipv6 = true`. `remote_sock()` в byedpi/proxy.c сам
  включает `IPV6_V6ONLY=0` на сокете назначения, когда семейство адреса
  назначения `AF_INET6` — этого достаточно, чтобы один и тот же baddr
  одинаково обслуживал и v4-mapped-v6 адреса (`map_fix(dst,6)` преобразует
  в них чистый IPv4 dst), и нативные IPv6-адреса. Проверено локально
  функциональным тестом, воспроизводящим точную логику `remote_sock()`/
  `map_fix()`: dual-stack сокет успешно подключается к обоим типам
  назначений и передаёт данные в обе стороны.
- **UDP fragmentation/MTU**: `tun2socks_bridge_run()` использует MTU=1500 по
  умолчанию; для мобильных сетей (LTE с меньшим MTU из-за инкапсуляции)
  может потребоваться подстройка — не протестировано на реальной мобильной
  сети.
- ~~**Split-tunnel self-exclusion bug**~~ — **исправлено**. В
  `BypassVpnService.runVpn()` раньше при непустом `allowedApps` вызывался
  `addAllowedApplication()`, а затем всё равно `addDisallowedApplication(
  packageName)` — Android бросал `UnsupportedOperationException` («Builder
  может иметь только allowed ИЛИ disallowed список, не оба»), которая молча
  проглатывалась. Теперь в allowed-режиме `addDisallowedApplication()` не
  вызывается вообще (`packageName` просто не входит в отфильтрованный
  allowed-список), а в disallowed-режиме (`allowedApps` пуст) оба вызова
  корректны, т.к. `addAllowedApplication()` в этой ветке не используется.

---

## 2. ECH (Encrypted Client Hello) — план остаётся актуальным

### 2.1 Почему это не "добавить флаг"

ECH — это не модификация ClientHello руками (как split/disorder в
PacketProcessor), а полноценная HPKE-операция:

1. Получить **ECH-конфигурацию** сервера — это TXT/HTTPS-запись DNS
   (`type65 HTTPS` с параметром `ech=...`) для домена. Наш `DohResolver`
   сейчас резолвит только A/AAAA — нужно добавить парсинг HTTPS/SVCB-записей
   через DoH (RFC 9460).
2. Сформировать **ClientHelloInner** (настоящий, с реальным SNI) и
   **ClientHelloOuter** (с публичным `public_name` вместо SNI) —
   зашифровать Inner через HPKE (`X25519 + HKDF-SHA256 + AES-128-GCM` — набор,
   который использует Chrome/Cloudflare) публичным ключом из ECH-конфига.
3. TLS-стек **должен уметь это делать сам** — вручную патчить байты
   ClientHello (как делает `PacketProcessor` для split) для ECH невозможно,
   потому что нужна полноценная HPKE-криптография и валидный TLS 1.3
   handshake state machine.
4. Единственный практичный путь на Android — использовать TLS-библиотеку с
   поддержкой ECH: **BoringSSL** (собранный с `--enable-ech`, как это делает
   Chrome/Cronet) — обычный Android `SSLEngine`/`javax.net.ssl` ECH не
   поддерживает. Значит: собрать `boringssl` под NDK (это тяжёлая, но хорошо
   документированная сборка, ~10-15 минут CI-времени), обернуть через JNI
   как `ssl_ech_jni.so`, и **весь TLS-трафик приложения должен идти через
   этот стек**, а не через системный `SSLEngine` — то есть фактически нужен
   пользовательский SNI-прокси (аналог `sslocal`/`v2ray`), который терминирует
   TLS от клиента... что для прозрачного VPN (TUN-режим, произвольные
   приложения) означает MITM внутри самого VPN-сервиса, а это отдельный
   архитектурный слой (TLS-интерцептирующий прокси на локальном сертификате),
   не совместимый с "трафик не покидает устройство и не расшифровывается"
   принципом текущего README.

### 2.2 Более реалистичная альтернатива для того же результата

Так как цель ECH — "скрыть SNI от DPI", а не именно ECH-протокол как таковой,
дешевле и быстрее по времени реализации (без BoringSSL-сборки) дать 80% эффекта:

- **Domain fronting через DoH HTTPS-записи** (получаем "front" домен с высокой
  репутацией, не блокируемый DPI, и коннектимся туда с реальным SNI внутри
  зашифрованного TLS record — актуально там, где DPI смотрит только на SNI,
  а не делает полный ECH-детект).
- **oobPosition/tlsRec фрагментация SNI-байтов** (уже реализовано в ciadpi
  через `-o`/`-r`, активно во всех текущих пресетах) — рвёт сигнатуру SNI
  без полноценного ECH.

Эти меры уже частично покрыты существующими пресетами (`oobPosition`,
`tlsRec`). Настоящий ECH стоит планировать отдельным релизом v4.0 с явным
выделением времени на сборку BoringSSL под NDK и тестирование на реальном
трафике.

### 2.3 Рекомендация

tun2socks (п.1), split-tunnel self-exclusion и IPv6 в ciadpi уже реализованы
и E2E-протестированы (в разной степени — см. таблицу ниже). Следующим
логичным шагом перед ECH является:
1. Реальная сборка через Android NDK (подтверждена зелёным CI-прогоном на
   GitHub Actions) + ручной прогон на реальном устройстве/эмуляторе —
   подтвердить, что путь работает не только на Linux x86_64 и в CI, но и
   при реальном использовании конечным пользователем.
2. Только затем — отдельная ветка `feature/ech` с CI-джобой, которая явно
   собирает BoringSSL (добавит 10-15 минут к каждому CI-прогону,
   нежелательно мешать с основной веткой, пока не стабилизировано).

---

## Итог

| Пункт roadmap | Статус | Что осталось |
|---|---|---|
| tun2socks native | ✅ Реализовано, E2E-протестировано на Linux, зелёный CI на Android NDK | Ручной тест на реальном устройстве |
| ECH | План задокументирован | Сборка BoringSSL-ECH под NDK, JNI-обёртка, HTTPS/SVCB-парсинг в DohResolver — отдельный релиз v4.0 |
| IPv6 TCP split (Kotlin fallback) | ✅ Сделано | — |
| DoH-клиент (перехват DNS) | ✅ Сделано (DnsInterceptor.kt) | — |
| Пресеты СНГ + ASN автоопределение | ✅ Сделано | Ручная калибровка TTL под реальные измерения в полевых условиях |
| IPv6 в ciadpi SOCKS5 назначениях | ✅ Сделано (dual-stack baddr), проверено функциональным тестом | — |
| Split-tunnel self-exclusion bug | ✅ Исправлено | — |
