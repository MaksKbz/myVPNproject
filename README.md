# myVPNproject v3.0 — Android DPI Bypass

Android-приложение для обхода DPI-блокировок без root. Вдохновлено проектами
[B4 (Bye Bye Big Bro)](https://github.com/DanielLavrushin/b4),
[ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) и
[Zapret](https://github.com/bol-van/zapret).

---

## Архитектура v3.0

```
Приложение → TUN (VpnService) → tun2socks (C/NDK) → ciadpi SOCKS5 (C/NDK) → интернет
```

**Ключевое отличие от v1/v2:** трафик больше не обрабатывается самописным
Kotlin-кодом. Весь DPI-обход делает нативная C-библиотека `ciadpi` (ByeDPI),
скомпилированная через Android NDK. Kotlin/Compose — только UI-слой.

### Компоненты

| Компонент | Язык | Роль |
|---|---|---|
| `BypassVpnService.kt` | Kotlin | TUN-интерфейс, foreground service |
| `ProxyEngine.kt` | Kotlin + JNI | Запуск/остановка ciadpi и tun2socks |
| `ConfigManager.kt` | Kotlin | Пресеты, сериализация CLI-аргументов |
| `MainActivity.kt` | Kotlin/Compose | UI: пресеты, приложения, справка |
| `ciadpi_jni.c` | C | JNI-обёртка для ciadpi движка |
| `tun2socks_jni.c` | C | JNI-обёртка для TUN→SOCKS5 моста |
| `ciadpi/` (submodule) | C | Движок ByeDPI — реальный DPI-обход |
| `tun2socks/` (submodule) | C | Мост TUN → SOCKS5 |

---

## Методы обхода DPI

| Метод | Параметр | Описание |
|---|---|---|
| Split | `-s` | Разбивает пакет по смещению |
| Disorder | `-d` | Разбивает + обратный порядок |
| OOB | `-o` | Out-of-Band данные |
| Fake + TTL | `-f -t` | Ложный пакет (доходит до DPI, не до сервера) |
| TLS Record Split | `-r` | Разрезает TLS-запись по SNI |
| Drop SACK | `-Y` | Заставляет переотправку — запутывает DPI |
| HTTP mod | `-M` | Изменяет регистр заголовков Host |
| Auto-mode | `-A` | Автопереключение при блокировке |

---

## Пресеты

| ID | Название | CLI-аргументы |
|---|---|---|
| `universal` | Универсальный | `-p 1080 -a 1 -o 1+s -d 1 -A n` |
| `youtube` | YouTube (агрессивный) | `-p 1080 -s 1 -d 1 -o 1+s -f -t 8 -r 1+s -Y -A r,t,s` |
| `telegram` | Telegram | `-p 1080 -s 1 -d 1 -f -t 8 -M h,d,r -A t,r,s` |
| `minimal` | Минимальный | `-p 1080 -s 1 -o 1` |
| `aggressive` | Максимальный | `-p 1080 -s 1+s -d 1+s -f -t 8 -Y -M h,d,r -r 1+s` |

---

## Сборка

### Требования

- Android Studio Koala+
- JDK 17+
- Android SDK API 24+
- **Android NDK r25+** (для C-библиотек)
- **CMake 3.22.1+**

### 1. Клонирование с submodule

```bash
git clone --recurse-submodules https://github.com/MaksKbz/myVPNproject.git
cd myVPNproject

# Если уже клонирован без --recurse-submodules:
git submodule update --init --recursive
```

### 2. Добавление C-движков (submodule)

```bash
# ByeDPI — ciadpi SOCKS5-движок
git submodule add https://github.com/hufrea/byedpi.git app/src/main/jni/ciadpi

# tun2socks мост
git submodule add https://github.com/ambrop72/badvpn.git app/src/main/jni/tun2socks

git submodule update --init --recursive
```

### 3. Сборка APK

```bash
# Debug
./gradlew assembleDebug

# Release (требует keystore)
./gradlew assembleRelease
```

**Результат:**
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

### 4. Подписание Release APK

```bash
keytool -genkey -v -keystore myvpn.keystore -alias myvpn -keyalg RSA -keysize 2048 -validity 10000

./gradlew assembleRelease

apksigner sign --ks myvpn.keystore --out app-release.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Активация нативного движка

После добавления submodule раскомментируйте `#include` в JNI-файлах:

**`app/src/main/jni/ciadpi_jni.c`:**
```c
// Раскомментировать:
#include "ciadpi/main.h"
// Заменить заглушку на:
int result = ciadpi_main(argc, argv);
```

**`app/src/main/jni/tun2socks_jni.c`:**
```c
// Раскомментировать:
#include "tun2socks/tun2socks.h"
// Заменить заглушку на:
tun2socks_main(tunFd, socksAddr, socksPort);
```

---

## Ссылки

- [ByeDPI (ciadpi)](https://github.com/hufrea/byedpi) — C-движок DPI-обхода
- [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) — референсная Android-реализация
- [B4 документация](https://daniellavrushin.github.io/b4/) — источник пресетов
- [badvpn / tun2socks](https://github.com/ambrop72/badvpn) — TUN→SOCKS5 мост
