# myVPNproject v3.1.1 — Android DPI Bypass

Android-приложение для обхода DPI-блокировок без root и без внешнего сервера.  
Весь трафик обрабатывается локально на устройстве через TUN-интерфейс.

Вдохновлено проектами
[ByeDPI (ciadpi)](https://github.com/hufrea/byedpi),
[ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) и
[Zapret](https://github.com/bol-van/zapret).

---

## Архитектура v3.1.1 (Kotlin-only, стабильная)

```
Приложение → TUN (VpnService) → PacketProcessor.kt (Kotlin) → изменённые пакеты → интернет
```

Текущая стабильная версия использует Kotlin userspace-движок.  
Native JNI-движок (ciadpi/tun2socks) запланирован как следующий этап — см. `AUDIT_AND_ROADMAP.md`.

### Компоненты

| Компонент | Роль |
|---|---|
| `BypassVpnService.kt` | TUN-интерфейс, foreground service, цикл чтения пакетов |
| `PacketProcessor.kt` | TCP-фрагментация TLS ClientHello, блокировка QUIC/UDP 443 |
| `ConfigManager.kt` | Пресеты, JSON-сериализация конфигурации (без Gson) |
| `MainActivity.kt` | UI: Jetpack Compose — пресеты, приложения, справка |

---

## Методы обхода DPI

| Метод | Описание |
|---|---|
| TCP Split | TLS ClientHello делится на 2 фрагмента (3 + остаток байт) |
| QUIC drop | UDP/443 дропается → браузер fallback на TCP/TLS |
| Checksum recalc | IP и TCP чексуммы пересчитываются для каждого фрагмента |
| SEQ shift | TCP Sequence Number второго фрагмента корректируется |

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

### Требования

- Android Studio Hedgehog+
- JDK 17+
- Android SDK API 24+
- NDK **не требуется** (Kotlin-only архитектура)

### Клонирование и сборка

```bash
git clone https://github.com/MaksKbz/myVPNproject.git
cd myVPNproject
./gradlew assembleDebug
```

**Результат:** `app/build/outputs/apk/debug/app-debug.apk`

### Release APK

```bash
keytool -genkey -v -keystore myvpn.keystore -alias myvpn -keyalg RSA -keysize 2048 -validity 10000
./gradlew assembleRelease
apksigner sign --ks myvpn.keystore --out app-release.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## CI / GitHub Actions

Автоматическая сборка Debug APK при каждом push в `master`.  
Artifact доступен в разделе [Actions](https://github.com/MaksKbz/myVPNproject/actions).

---

## Дорожная карта

Полный аудит, список оставшихся проблем и план улучшений:
👉 **[AUDIT_AND_ROADMAP.md](./AUDIT_AND_ROADMAP.md)**

---

## Ссылки

- [ByeDPI (ciadpi)](https://github.com/hufrea/byedpi) — референсный C-движок DPI-обхода
- [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) — Android-реализация ciadpi
- [B4 документация](https://daniellavrushin.github.io/b4/) — источник пресетов
- [Zapret](https://github.com/bol-van/zapret) — альтернативный DPI-обход
- [B4 Android DPI Bypass Guide](./B4_Android_DPI_Bypass_Guide.md) — внутренний гайд
