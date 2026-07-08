# tun2socks (badvpn) и ECH — точный инженерный план

**Статус:** НЕ реализовано в этом коммите намеренно.
**Причина:** оба пункта требуют компиляции нативного C/C++ кода под Android NDK
и реального запуска на устройстве/эмуляторе для проверки. В текущей рабочей
сессии инструмент выполнения shell-команд был недоступен (не отвечал ни на одну
команду), поэтому любой "рабочий" C-код, вставленный без сборки, был бы
непроверяемым враньём в кодовой базе. Ниже — честный, воспроизводимый план,
который можно выполнить за один присест с доступной консолью.

---

## 1. tun2socks (badvpn) — что реально нужно сделать

### 1.1 Ключевой факт, который меняет план

В README/NATIVE_ENGINE_SETUP.md написано, что после `git submodule add badvpn`
достаточно раскомментировать вызовы `tun2socks_run()` / `tun2socks_stop()`.
**Это неверно** — я проверил актуальный исходник
[`badvpn/tun2socks/tun2socks.c`](https://github.com/ambrop72/badvpn/blob/master/tun2socks/tun2socks.c)
и [`tun2socks.h`](https://github.com/ambrop72/badvpn/blob/master/tun2socks/tun2socks.h):

- Там **нет** функций `tun2socks_run()` / `tun2socks_stop()` — это монолитный
  `int main(argc, argv)`, который сам парсит CLI-аргументы, сам открывает TUN
  по имени устройства (`--tundev`) через `BTap`, и работает в собственном
  `BReactor`-event loop до `SIGINT`/`SIGTERM`.
- `tun2socks.h` — это просто файл констант (`CLIENT_SOCKS_RECV_BUF_SIZE` и т.п.),
  не публичный API.
- Библиотека построена на связке **lwIP** (стек TCP/IP) + **BadVPN core**
  (BReactor/BSocksClient/BTap) — обе зависимости сами по себе представляют
  ~15-20 файлов, которые CMake должен собрать как отдельные статические
  библиотеки (`liblwip.a`, `libbadvpn-*.a`) до сборки `tun2socks_jni.so`.

### 1.2 Два реальных пути

**Вариант A — subprocess/exec (быстрее, менее интегрированный)**

1. Собрать `badvpn-tun2socks` как отдельный **исполняемый файл** (не .so) для
   каждого ABI через `ndk-build`/CMake `add_executable`, положить в
   `app/src/main/jniLibs/<abi>/libtun2socks_bin.so` (Android разрешает грузить
   произвольные ELF-бинарники из `jniLibs`, если их имя начинается с `lib` и
   заканчивается `.so` — это стандартный трюк для upstream-бинарников без
   изменения кода).
2. В Kotlin (`ProxyEngine.kt`) вместо JNI-вызова — `ProcessBuilder` с
   аргументами `--netif-ipaddr 10.0.0.2 --netif-netmask 255.255.255.0
   --socks-server-addr 127.0.0.1:1080 --tunfd <fd>`.
   **Важно:** апстримный tun2socks принимает `--tundev <имя>` и сам открывает
   `/dev/tun`, что на Android недоступно без root. Нужен патч (см. форки
   `heiher/hev-socks5-tunnel` или существующий патч в ByeDPIAndroid/
   Outline-android — они уже добавили `--tunfd` в свои форки badvpn именно
   под этот кейс). Это единственная реальная модификация исходников,
   без которой Android-версия невозможна.
3. Передать уже открытый `ParcelFileDescriptor` через `--tunfd` (числовой fd,
   который остаётся валидным между процессами при правильном
   `Process.Builder` + `ProcessBuilder.redirectInput`/passing fd через
   `/proc/self/fd` трюк, как это делает V2rayNG/Outline).

**Вариант B — прямой JNI (полный zero-copy, сложнее)**

1. Добавить submodule `badvpn` + submodule `lwip` (badvpn использует внешний lwIP).
2. В CMakeLists.txt собрать lwIP как статическую библиотеку с
   `LWIP_ANDROID`-совместимым `lwipopts.h` (Android NDK не даёт `sys/socket.h`
   BSD-специфики, которые lwIP ожидает — нужен `arch/sys_arch.c` под Android,
   аналогичный тому, что использует `shadowsocks-android`/`Outline`).
3. Отрефакторить `tun2socks.c`: вынести `main()` в `tun2socks_start(int tun_fd,
   const char* socks_addr, int socks_port)` / `tun2socks_stop()`, убрать
   `BLog`-инициализацию через argv, инициализировать `BReactor` в отдельном
   потоке (сейчас `main()` блокирует текущий поток — под JNI это ОК, если
   вызывать из выделенного pthread, как уже сделано в `tun2socks_jni.c`
   каркасе).
4. Собрать и прогнать `assembleDebug` под всеми тремя ABI (`arm64-v8a`,
   `armeabi-v7a`, `x86_64`) — падения возможны на несовпадении `size_t`/
   `socklen_t` типов между lwIP и Bionic libc.

### 1.3 Рекомендация

Вариант A (subprocess + патч `--tunfd`, беря готовый патч из ByeDPIAndroid или
hev-socks5-tunnel) — реалистичнее за ограниченное время: даёт "-40% latency,
-30% CPU" из роадмапа за счёт ухода из Kotlin `PacketProcessor`-цикла, но без
необходимости портировать весь lwIP на Bionic с нуля.

**Действия при следующей рабочей сессии с bash:**
```bash
git submodule add https://github.com/ambrop72/badvpn.git app/src/main/jni/badvpn
git submodule update --init --recursive
# применить патч --tunfd (взять из ByeDPIAndroid/hev-socks5-tunnel как референс)
# собрать badvpn-tun2socks как add_executable в CMakeLists.txt под каждый ABI
./gradlew assembleDebug --stacktrace
# проверить на эмуляторе: adb logcat | grep tun2socks
```

---

## 2. ECH (Encrypted Client Hello) — что реально нужно сделать

### 2.1 Почему это не "добавить флаг"

ECH — это не modификация ClientHello руками (как split/disorder в
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
- **oobPosition/tlsRec фрагментация SNI-байтов** (уже частично реализовано
  в ciadpi через `-o`/`-r`) — рвёт сигнатуру SNI без полноценного ECH.

Эти меры уже частично покрыты существующими пресетами (`oobPosition`,
`tlsRec`). Настоящий ECH стоит планировать отдельным релизом v4.0 с явным
выделением времени на сборку BoringSSL под NDK и тестирование на реальном
трафике.

### 2.3 Рекомендация

Не начинать ECH до тех пор, пока:
1. tun2socks (п.1) не переведён на нативный путь и не протестирован —
   ECH усложнит и без того непроверенный нативный слой.
2. Не выделена отдельная ветка `feature/ech` с CI-джобой, которая явно
   собирает BoringSSL (это добавит 10-15 минут к каждому CI-прогону,
   нежелательно мешать с основной веткой, пока не стабилизировано).

---

## Итог

| Пункт roadmap | Готовность после этой сессии | Что реально нужно для завершения |
|---|---|---|
| tun2socks native | План задокументирован, код не тронут (был бы непроверяемым) | Патч `--tunfd` в badvpn (или полный JNI-рефакторинг с lwIP), сборка на реальном bash/CI |
| ECH | План задокументирован | Сборка BoringSSL-ECH под NDK, JNI-обёртка, HTTPS/SVCB-парсинг в DohResolver — отдельный релиз v4.0 |
| IPv6 TCP split | ✅ Сделано в этом коммите | — |
| DoH-клиент (перехват DNS) | ✅ Сделано в этом коммите (DnsInterceptor.kt) | — |
| Пресеты СНГ + ASN автоопределение | ✅ Сделано в этом коммите | Ручная калибровка TTL под реальные измерения в полевых условиях |
