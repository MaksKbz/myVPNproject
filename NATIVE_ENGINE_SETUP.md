# Активация нативного движка (byedpi + tun2socks)

## Архитектура

```
Браузер/приложение
       ↓
   TUN-интерфейс (10.0.0.2/24)
       ↓
  tun2socks (badvpn — C, JNI)
  Читает IP-пакеты из TUN fd,
  устанавливает реальные TCP-соединения через VpnService.protect()
       ↓
   SOCKS5  127.0.0.1:1080
       ↓
  ciadpi (byedpi — C, JNI)
  DPI-обход: TCP split, disorder, fake, OOB, TLS record split
       ↓
     Интернет
```

## Шаги активации

### 1. Добавить submodule byedpi

```bash
git submodule add https://github.com/hufrea/byedpi.git \
  app/src/main/jni/byedpi
git submodule update --init --recursive
```

### 2. Добавить submodule badvpn (tun2socks)

```bash
git submodule add https://github.com/ambrop72/badvpn.git \
  app/src/main/jni/badvpn
git submodule update --init --recursive
```

### 3. Раскомментировать #include в C-файлах

В `ciadpi_jni.c`:
```c
// Раскомментировать:
#include "byedpi/proxy.h"
#include "byedpi/params.h"

// В ciadpi_thread() заменить заглушку на:
struct Params p = params_from_config(&g_config);
proxy_run(&p);

// В ciadpiStop() раскомментировать:
proxy_stop();
```

В `tun2socks_jni.c`:
```c
// Раскомментировать:
#include "badvpn/tun2socks/tun2socks.h"

// В tun2socks_thread() заменить заглушку на:
tun2socks_run(g_config.tun_fd, "127.0.0.1", g_config.socks_port,
              g_config.tun_addr, g_config.tun_gw, g_config.tun_prefix);

// В tun2socksStop() раскомментировать:
tun2socks_stop();
```

### 4. Обновить CI для сборки NDK

В `.github/workflows/android.yml` добавить установку NDK:

```yaml
- name: Setup NDK
  uses: android-actions/setup-android@v3
  with:
    ndk-version: '26.3.11579264'
```

Или через `local.properties`:
```
ndk.dir=/path/to/ndk
```

### 5. Проверить сборку

```bash
git submodule update --init --recursive
./gradlew assembleDebug --stacktrace
```

## Текущий статус (stub-режим)

Без submodule-ов приложение компилируется, VPN-туннель поднимается,
нативные библиотеки загружаются (`UnsatisfiedLinkError` логируется как warning).
Реальный форвардинг трафика активируется после добавления submodule-ов
и раскомментирования вызовов.

## Ссылки

- byedpi (ciadpi): https://github.com/hufrea/byedpi
- badvpn (tun2socks): https://github.com/ambrop72/badvpn
- Android NDK JNI Guide: https://developer.android.com/ndk/guides/jni
