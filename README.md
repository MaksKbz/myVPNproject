# myVPNproject v3.3.0-native — Android DPI Bypass

Android-приложение для обхода DPI-блокировок без root и без внешнего сервера.  
Трафик перехватывается локально через TUN-интерфейс и форвардируется через нативный движок ciadpi.

Вдохновлено проектами
[ByeDPI (ciadpi)](https://github.com/hufrea/byedpi),
[ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) и
[Zapret](https://github.com/bol-van/zapret).

---

## Архитектура v3.3.0-native

```
Браузер/приложение
       ↓
   TUN-интерфейс (10.0.0.2/24)    ←— BypassVpnService.kt
       ↓
  tun2socks  (badvpn, C, JNI)           ←— tun2socks_jni.so
  Читает IP-пакеты из TUN fd,
  устанавливает TCP-соединения через VpnService.protect()
       ↓
   SOCKS5  127.0.0.1:1080
       ↓
  ciadpi  (byedpi, C, JNI)              ←— ciadpi_jni.so
  TCP split / disorder / fake / OOB / TLS record split
       ↓
     Интернет
```

### Компоненты

| Компонент | Роль |
|---|---|
| `BypassVpnService.kt` | TUN-интерфейс, foreground service, передаёт `tunFd` в `ProxyEngine` |
| `ProxyEngine.kt` | Kotlin JNI-мост: запуск ciadpi → tun2socks |
| `ciadpi_jni.c` | C JNI-мост для byedpi (stub → реальный после submodule) |
| `tun2socks_jni.c` | C JNI-мост для badvpn/tun2socks (stub → реальный) |
| `ConfigManager.kt` | Пресеты, JSON-сериализация конфигурации |
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

---

## Сборка

### Stub-режим (без submodule)

```bash
git clone https://github.com/MaksKbz/myVPNproject.git
cd myVPNproject
git checkout feature/native-engine
./gradlew assembleDebug
```

APK собирается. VPN-туннель поднимается, но DPI-обход ещё не активен — stub.

### Полная активация (byedpi + badvpn)

```bash
# 1. Добавить submodule-ы
git submodule add https://github.com/hufrea/byedpi.git \
  app/src/main/jni/byedpi
git submodule add https://github.com/ambrop72/badvpn.git \
  app/src/main/jni/badvpn
git submodule update --init --recursive

# 2. Раскомментировать #include в ciadpi_jni.c и tun2socks_jni.c
#    (см. NATIVE_ENGINE_SETUP.md)

# 3. Собрать
./gradlew assembleDebug
```

### Требования

- Android Studio Hedgehog+
- JDK 17+
- Android SDK API 24+
- **NDK 27.0.12077973** (устанавливается автоматически через CI или `sdkmanager`)

---

## CI / GitHub Actions

Автоматическая сборка Debug APK при каждом push в `master` и `feature/native-engine`.  
Степы: checkout (submodules:recursive) → JDK 17 → NDK 27 → Gradle → assembleDebug → upload artifact.

Artifact доступен в разделе [Actions](https://github.com/MaksKbz/myVPNproject/actions).

---

## Дорожная карта

- [x] Kotlin userspace TCP split (v3.2.0)
- [x] JNI каркас: CMake + ciadpi_jni + tun2socks_jni + ProxyEngine.kt (v3.3.0-native)
- [ ] Добавить submodule byedpi и раскомментировать proxy_run()
- [ ] Добавить submodule badvpn и раскомментировать tun2socks_run()
- [ ] Полное E2E-тестирование на реальном устройстве

---

## Ссылки

- [ByeDPI (ciadpi)](https://github.com/hufrea/byedpi) — референсный C-движок DPI-обхода
- [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) — Android-реализация ciadpi
- [badvpn/tun2socks](https://github.com/ambrop72/badvpn) — TUN → SOCKS5 прокси
- [Zapret](https://github.com/bol-van/zapret) — альтернативный DPI-обход
- [NATIVE_ENGINE_SETUP.md](./NATIVE_ENGINE_SETUP.md) — инструкция активации
