# Аудит и дорожная карта — myVPNproject

**Дата аудита:** 2026-07-07  
**Версия на момент аудита:** v3.1.1  
**Ветка:** `master`  
**Архитектура:** Kotlin-only userspace (PacketProcessor)

---

## 1. Итоговое состояние репозитория

После серии исправлений (07.07.2026) проект приведён в рабочее состояние:

```
myVPNproject/
├── .github/
│   └── workflows/android.yml     ✅ только master, FORCE_NODE24
├── app/
│   ├── build.gradle.kts          ✅ чистые зависимости, без CMake/Gson
│   └── src/main/java/.../vpn/
│       ├── BypassVpnService.kt   ✅ корректная сигнатура PacketProcessor
│       ├── ConfigManager.kt      ✅ org.json вместо Gson, getSharedPreferences
│       └── PacketProcessor.kt    ✅ TCP-фрагментация, QUIC-блокировка
├── README.md                     ✅ актуализирован под v3.1.1
├── B4_Android_DPI_Bypass_Guide.md
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── gradle/
```

### Что было исправлено в ходе сессии

| # | Файл | Проблема | Решение |
|---|---|---|---|
| 1 | `BypassVpnService.kt` | Неверная сигнатура `PacketProcessor.processPacket(buffer, length)` → падение `compileDebugKotlin` | Заменено на `processPacket(buffer.array(), length, output)` |
| 2 | `BypassVpnService.kt` | Отсутствовала константа `EXTRA_PRESET_ID`, на которую ссылался `MainActivity` | Добавлена `const val EXTRA_PRESET_ID = "PRESET_ID"` |
| 3 | `ConfigManager.kt` | Импорты `Gson` и `androidx.preference.PreferenceManager` без объявленных зависимостей → 6 ошибок компиляции | Переписано на `org.json.JSONObject` + `context.getSharedPreferences` (стандартный Android SDK) |
| 4 | `app/build.gradle.kts_dep_fix` | Устаревший мусорный файл, не участвующий в сборке | Удалён |
| 5 | `ProxyEngine.kt` | Осиротевший файл нативной архитектуры, не вызывается из активной цепочки | Удалён из `master` |
| 6 | `app/src/main/jni/*` | CMakeLists.txt, ciadpi_jni.c, tun2socks_jni.c — заглушки без submodule, создавали ложную архитектуру | Удалены из `master` |
| 7 | `scripts/activate_native.sh` | Скрипт для нативного слоя, неприменимый без submodule | Удалён из `master` |
| 8 | `.gitmodules` | Ссылки на submodule без самих submodule | Удалён из `master` |
| 9 | `.github/workflows/android.yml` | Триггеры на `master` и `main` → риск двойных запусков | Оставлена только ветка `master`; добавлена `env: FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true` |

---

## 2. Оставшиеся проблемы — требуют исправления

### 🔴 КРИТИЧЕСКАЯ — Race condition: общий буфер между потоками

**Файл:** `BypassVpnService.kt`  
**Метод:** `runVpn()`

**Проблема:**  
Главный поток читает следующий пакет в `buffer.array()` сразу после `buffer.clear()`,  
пока `CachedThreadPool`-поток ещё обрабатывает предыдущий из того же массива.  
Результат: `PacketProcessor` фрагментирует случайные/перезаписанные данные.

**Текущий код (неверно):**
```kotlin
val length = input.read(buffer.array())
if (length > 0) {
    executorService?.submit {
        PacketProcessor.processPacket(
            buffer.array(),   // ← тот же массив!
            length,
            output
        )
    }
    buffer.clear()  // ← тут же очищается и перезаписывается
}
```

**Исправление:**
```kotlin
val length = input.read(buffer.array())
if (length > 0) {
    val packetCopy = buffer.array().copyOf(length)  // копия до clear()
    executorService?.submit {
        PacketProcessor.processPacket(packetCopy, length, output)
    }
    buffer.clear()
}
```

---

### 🔴 КРИТИЧЕСКАЯ — Конкурентная запись в `output` без синхронизации

**Файл:** `BypassVpnService.kt` → `PacketProcessor.kt`  

**Проблема:**  
Несколько задач из `CachedThreadPool` одновременно вызывают `output.write(...)`.  
`PacketProcessor` пишет два фрагмента одного TLS ClientHello (pkt1 + pkt2),  
но между ними другой поток может вклинить запись другого пакета.  
DPI получит перемешанные байты — фрагментация не сработает.

**Исправление в `BypassVpnService.kt`:**
```kotlin
executorService?.submit {
    synchronized(output) {
        PacketProcessor.processPacket(packetCopy, length, output)
    }
}
```

---

### 🟡 СРЕДНЯЯ — `EXTRA_PRESET_ID` передаётся, но сервис его не читает

**Файл:** `BypassVpnService.kt` → `onStartCommand()`

**Проблема:**  
`MainActivity` отправляет `putExtra(EXTRA_PRESET_ID, selectedPresetId.value)`,  
но `onStartCommand` читает только `EXTRA_ALLOWED_APPS` и игнорирует пресет.  
Все пакеты всегда обрабатываются одинаково — выбор пресета пользователем не влияет на обработку.

**Исправление:**
```kotlin
// В onStartCommand:
val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)
val presetId = intent.getStringExtra(EXTRA_PRESET_ID) ?: "universal"
startVpn(allowedApps, presetId)

// В startVpn и runVpn — передать presetId в PacketProcessor
// В PacketProcessor — добавить параметр конфигурации
```

---

### 🟡 СРЕДНЯЯ — `Thread.sleep(1)` — лишняя задержка в цикле

**Файл:** `BypassVpnService.kt` → `runVpn()`, строка `Thread.sleep(1)`

**Проблема:**  
`input.read()` уже является блокирующим вызовом и ждёт данные сам.  
`Thread.sleep(1)` добавляет минимум 1 мс на каждый пакет.  
При 1000 пакетов/сек на HD-видео это ≈ 1 секунда суммарной задержки в секунду обработки.

**Исправление:** удалить строку `Thread.sleep(1)` целиком.

---

### 🟡 СРЕДНЯЯ — `isRunning` не `@Volatile`

**Файл:** `BypassVpnService.kt`

**Проблема:**  
Флаг `isRunning` пишется главным потоком и читается `vpnThread`.  
Без `@Volatile` JVM не гарантирует видимость изменений между потоками (JMM).  
Цикл `while (isRunning)` может не увидеть `false` и зависнуть.

**Исправление:**
```kotlin
@Volatile private var isRunning = false
```

---

### 🟢 НИЗКАЯ — `compileSdk`/`targetSdk = 34` устарел

**Файл:** `app/build.gradle.kts`

**Проблема:**  
Android 15 (API 35) стал стабильным. Google Play требует targetSdk ≥ 35 для новых приложений.

**Исправление:**
```kotlin
compileSdk = 35
targetSdk = 35
```

---

### 🟢 НИЗКАЯ — README описывает нативную архитектуру v3.0 (устарел)

**Файл:** `README.md`  
Описывает `ciadpi`, `tun2socks`, JNI-компоненты и submodule, которых больше нет в `master`.

**Исправление:** обновить README под текущую Kotlin-only архитектуру v3.1.1 (выполнено в этом же коммите).

---

## 3. Рекомендуемая последовательность следующих коммитов

| Приоритет | Шаг | Commit message |
|---|---|---|
| 🔴 1 | Добавить `packetCopy` в цикле чтения | `fix(vpn): copy packet buffer before async dispatch to prevent race condition` |
| 🔴 2 | Обернуть вызов в `synchronized(output)` | `fix(vpn): synchronize output stream writes across executor threads` |
| 🟡 3 | Пробросить `presetId` из Intent в `PacketProcessor` | `feat(vpn): wire preset selection to packet processing logic` |
| 🟡 4 | Удалить `Thread.sleep(1)` | `perf(vpn): remove redundant sleep in blocking read loop` |
| 🟡 5 | Добавить `@Volatile` на `isRunning` | `fix(vpn): mark isRunning volatile for cross-thread visibility` |
| 🟢 6 | Поднять `targetSdk = 35` | `chore(gradle): bump compileSdk and targetSdk to 35` |

---

## 4. Методика улучшения обхода блокировок

### Уровень 1 — Улучшить существующий Kotlin-движок

**Рандомизация точки сплита**  
Сейчас TLS ClientHello всегда делится на `3 + остаток`. Фиксированный паттерн
могут детектировать продвинутые DPI. Нужно случайно выбирать точку в диапазоне 1–5:

```kotlin
val frag1PayloadLen = (1..5).random()
```

**Поддержка TLS 1.3 ECH (Encrypted Client Hello)**  
ECH полностью скрывает SNI внутри зашифрованного расширения. Для работы нужно:
1. Использовать DNS over HTTPS (DoH) для получения ECH-ключей из HTTPS-записи домена
2. Передавать ключ в TLS ClientHello как расширение `encrypted_client_hello`

Это наиболее перспективный метод против современных DPI-систем.

**Детектор эффективности пресета**  
Автоматическое переключение пресета если текущий не работает:

```kotlin
// После establish() запускаем тест в корутине
CoroutineScope(Dispatchers.IO).launch {
    delay(3000)
    val ok = testConnectivity("https://cp.cloudflare.com")  
    if (!ok) switchToNextPreset()
}
```

---

### Уровень 2 — Поддержка IPv6

Сейчас `PacketProcessor` пропускает IPv6-пакеты без изменений:
```kotlin
if (ipVersion != 4) {
    output.write(packetBytes, 0, length)
    return true
}
```
Многие провайдеры блокируют только IPv4-трафик, оставляя IPv6 открытым.
Добавление зеркальной логики фрагментации для IPv6 (заголовок 40 байт вместо 20)
значительно расширит охват без изменения архитектуры.

---

### Уровень 3 — Переход на нативный движок ciadpi через JNI

Kotlin userspace имеет принципиальный потолок производительности:
- Каждый пакет копируется из JNI-буфера в JVM heap
- GC-паузы влияют на латентность обработки
- При 50+ Мбит/с это станет узким местом

**Нативный движок ciadpi (byedpi)** обрабатывает пакеты в C без JVM:

```bash
# Шаги для активации нативного слоя (в отдельной ветке native-engine):
git submodule add https://github.com/hufrea/byedpi.git app/src/main/jni/ciadpi
git submodule add https://github.com/ambrop72/badvpn.git app/src/main/jni/tun2socks
git submodule update --init --recursive
```

Затем создать ветку `native-engine` и восстановить JNI-файлы из истории git.
PR из `native-engine` → `master` делать только после полного прохождения CI.

---

### Уровень 4 — Foreground Service + Уведомление

Сейчас `BypassVpnService` не вызывает `startForeground()`.  
На Android 8+ (API 26) сервис без foreground-уведомления будет убит системой  
через несколько минут при сворачивании приложения.

```kotlin
// В startVpn() добавить:
val notification = NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("myVPNproject активен")
    .setContentText("DPI bypass работает")
    .setSmallIcon(R.drawable.ic_vpn)
    .build()
startForeground(NOTIFICATION_ID, notification)
```

Также нужно зарегистрировать `NotificationChannel` в `Application.onCreate()`.

---

## 5. Минимальный критерий готовности v3.2

- [ ] `@Volatile` на `isRunning`
- [ ] `packetCopy` перед async dispatch
- [ ] `synchronized(output)` вокруг `PacketProcessor.processPacket`
- [ ] `Thread.sleep(1)` удалён
- [ ] `presetId` читается в `onStartCommand` и влияет на обработку
- [ ] `startForeground()` вызывается при старте VPN
- [ ] `targetSdk = 35`
- [ ] `assembleDebug` и `assembleRelease` проходят в CI

---

*Документ сгенерирован автоматически по результатам аудита сессии 07.07.2026.*
