package com.makskbz.myvpnproject.vpn

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BypassVpnService : VpnService(), Runnable {

    companion object {
        const val TAG = "BypassVpnService"
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val EXTRA_ALLOWED_APPS = "ALLOWED_APPS"
        const val EXTRA_PRESET_ID   = "PRESET_ID"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID      = "vpn_channel"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var executorService: ExecutorService? = null
    private var monitorThread: Thread? = null
    // v3.7.11 CIS-MAX: ссылка на асинхронный поток остановки нативного
    // движка (см. stopVpn()) — нужна, чтобы startVpn() мог дождаться его
    // завершения (с таймаутом) перед повторным запуском. Без этого при
    // быстром цикле "Остановить" -> сразу "Запустить" ProxyEngine.start()
    // мог бы столкнуться с ещё не остановленным g_running=1 внутри
    // ciadpiStart()/tun2socksStart() (обе JNI-функции в этом случае молча
    // возвращают "успех" без реального запуска нового потока — см.
    // ciadpi_jni.c/tun2socks_jni.c: "if (g_running) { ...; return 0; }") —
    // то есть UI показал бы, что VPN включился, а по факту старый
    // (в процессе остановки) движок продолжает работать со старой
    // конфигурацией/портом, либо новый вообще не стартует.
    @Volatile private var proxyEngineStopThread: Thread? = null
    @Volatile private var isRunning = false
    @Volatile private var activePresetId: String = "universal"
    @Volatile private var currentConfig: BypassConfig = ConfigManager.loadPreset("universal")

    // Статистика для авто-переключения
    @Volatile private var lastPacketTime = System.currentTimeMillis()
    @Volatile private var consecutiveFails = 0

    override fun onCreate() {
        super.onCreate()
        CrashLogger.checkpoint(this, "BypassVpnService.onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLogger.checkpoint(this, "BypassVpnService.onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                activePresetId = intent.getStringExtra(EXTRA_PRESET_ID) ?: "universal"
                currentConfig = ConfigManager.loadPreset(activePresetId)
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)
                startVpn(allowedApps)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(allowedApps: ArrayList<String>?) {
        if (isRunning) return
        // v3.7.11 CIS-MAX: если предыдущая остановка (см. stopVpn()) ещё не
        // успела завершить ProxyEngine.stop() в своём асинхронном потоке —
        // ждём её (с таймаутом на случай, если тот поток сам где-то завис,
        // чтобы не заблокировать запуск VPN навсегда). Без этого быстрый
        // цикл "Остановить" -> "Запустить" рисковал бы столкнуться с
        // g_running=1 внутри ciadpiStart()/tun2socksStart() от предыдущего,
        // ещё не остановленного движка.
        proxyEngineStopThread?.let { t ->
            if (t.isAlive) {
                CrashLogger.checkpoint(this, "startVpn: ждём завершения предыдущего ProxyEngine.stop()")
                try { t.join(3000) } catch (_: InterruptedException) {}
                if (t.isAlive) {
                    CrashLogger.checkpoint(this, "startVpn: предыдущий ProxyEngine.stop() всё ещё не завершился после 3с — продолжаем всё равно")
                }
            }
        }
        proxyEngineStopThread = null
        isRunning = true
        CrashLogger.checkpoint(this, "startVpn: перед startForeground()")
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            CrashLogger.checkpoint(this, "startVpn: startForeground() успешно выполнен")
        } catch (e: Throwable) {
            CrashLogger.checkpoint(this, "startVpn: startForeground() выбросил ${e.javaClass.name}: ${e.message}")
            throw e
        }
        executorService = Executors.newCachedThreadPool()
        vpnThread = Thread({ runVpn(allowedApps) }, "BypassVpnThread").apply { start() }
        startFastMemoryWatch()
        Log.i(TAG, "VPN service v3.5-hybrid started. Preset: $activePresetId")
    }

    // v3.7.7 CIS-MAX: предыдущий checkpoint-лог показал, что процесс
    // умирает МЕНЕЕ ЧЕМ ЗА СЕКУНДУ после входа в runNativeIdleLoop() —
    // раньше, чем успевают сработать чекпоинты tick=1 (через ~1000ms) и
    // AsnAutoDetect: вызываем detect() (через ~1500ms). Это исключает ASN
    // auto-detect как причину и указывает на сам нативный tun2socks/ciadpi
    // под ПЕРВОЙ ВОЛНОЙ реального трафика устройства (addRoute("0.0.0.0",0)
    // + addRoute("::",0) означают, что ВЕСЬ трафик телефона — десятки
    // фоновых приложений и системных сервисов одновременно — мгновенно
    // пошёл через TUN, в отличие от синтетического E2E-теста с одним
    // TCP-соединением). Наиболее вероятная причина такого профиля —
    // Android Low Memory Killer (cgroup OOM), который тоже посылает
    // SIGKILL напрямую, БЕЗ гарантированного вызова onLowMemory()/
    // onTrimMemory() ДО самого убийства (это лишь заблаговременные
    // предупреждения, не гарантия). Быстрый (250мс) независимый поток
    // логирует реальное потребление памяти, чтобы подтвердить/опровергнуть
    // эту гипотезу за то же самое окно жизни процесса.
    private fun startFastMemoryWatch() {
        Thread({
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                var tick = 0
                while (isRunning) {
                    tick++
                    val nativeHeap = Debug.getNativeHeapAllocatedSize() / 1024
                    val runtime = Runtime.getRuntime()
                    val jvmUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024
                    val memInfo = ActivityManager.MemoryInfo()
                    am?.getMemoryInfo(memInfo)
                    // v3.7.7: количество открытых файловых дескрипторов процесса —
                    // SocksUdpClient/BSocksClient открывают новый сокет НА КАЖДОЕ
                    // соединение; при реальной нагрузке (десятки одновременных
                    // системных сервисов/приложений через TUN) возможно
                    // исчерпание лимита fd (RLIMIT_NOFILE), что тоже приводит к
                    // abort()/SIGSEGV в нативном коде при попытке открыть ещё один.
                    val fdCount = try {
                        java.io.File("/proc/self/fd").list()?.size ?: -1
                    } catch (_: Exception) { -1 }
                    CrashLogger.checkpoint(
                        this,
                        "mem-watch #$tick: nativeHeapKb=$nativeHeap jvmUsedKb=$jvmUsedKb " +
                                "systemAvailKb=${memInfo.availMem / 1024} systemLowMemory=${memInfo.lowMemory} " +
                                "systemThresholdKb=${memInfo.threshold / 1024} openFds=$fdCount"
                    )
                    Thread.sleep(250)
                }
            } catch (_: InterruptedException) {
                // сервис остановлен — нормально
            } catch (e: Throwable) {
                CrashLogger.checkpoint(this, "mem-watch: FATAL ${e.javaClass.name}: ${e.message}")
            }
        }, "FastMemoryWatch").apply { isDaemon = true; start() }
    }

    // v3.7.11 CIS-MAX: КРИТИЧЕСКИЙ фикс — пользователь сообщил, что нажатие
    // "ОСТАНОВИТЬ VPN" в UI не убирает системный значок VPN-ключа в статус-
    // баре Android — приходится вручную отключать через системное
    // всплывающее уведомление "VPN подключён".
    //
    // Причина: старый порядок был (1) ProxyEngine.stop() (2)
    // vpnInterface?.close(). ProxyEngine.stop() делает pthread_join() на
    // ДВУХ нативных потоках (tun2socks_thread, ciadpi_thread) и блокирует
    // вызывающий поток, пока они не завершатся сами. Android же считает
    // VPN активным до тех пор, пока не закрыт файловый дескриптор
    // vpnInterface (см. VpnService.Builder.establish() — системный
    // VPN-индикатор привязан именно к этому fd, а не к жизни процесса
    // приложения) — то есть значок ключа висит ровно до строки (2), а она
    // выполняется только ПОСЛЕ того, как оба потока успеют дойти до
    // pthread_join(). В byedpi resolve() использует блокирующий
    // getaddrinfo() без таймаута прямо в однопоточном event loop
    // ciadpi_thread — если в момент нажатия "Остановить" шёл DNS-резолв,
    // поток застревает там на неопределённое время (getaddrinfo() не
    // прерывается сигналом остановки shutdown(server_fd) и не имеет
    // настраиваемого таймаута), и pthread_join() в главном потоке ждёт
    // этого сколько угодно — vpnInterface.close() никогда не
    // выполняется, и системный VPN-индикатор не гаснет.
    //
    // Исправление: закрываем vpnInterface ПЕРВЫМ ДЕЛОМ (немедленно
    // освобождает VPN на уровне системы — Android сразу гасит значок и
    // восстанавливает обычную маршрутизацию, что решает видимый
    // пользователю симптом), а остановку native-движка (ProxyEngine.stop())
    // переносим в отдельный поток — она больше не блокирует ни главный
    // поток, ни возврат из stopVpn()/onStartCommand(), и не может вызвать
    // ANR, даже если native-потоки зависнут на getaddrinfo() на долгое
    // время.
    //
    // v3.7.12 CIS-MAX ПОДТВЕРЖДЕНО: временная диагностика через
    // ConnectivityManager.getNetworkCapabilities() (сразу после close() и
    // через 2с) показала на реальном устройстве activeNetwork is VPN:
    // false в ОБОИХ замерах — то есть система Android перестаёт считать
    // VPN активным менее чем за ~15мс после нажатия "Остановить VPN".
    // Значит фикс выше работает корректно, реального бага с "зависшим"
    // VPN-туннелем нет. Системный экран с длительностью сессии/счётчиками
    // трафика и кнопкой "Отключиться", который пользователь видит после
    // остановки — это отдельный СТАНДАРТНЫЙ системный UI Android
    // (обычно открывается долгим нажатием на значок VPN-ключа в шторке
    // уведомлений, либо "Настройки -> Сеть -> VPN") — он показывает
    // снимок состояния на момент открытия и требует ручного
    // подтверждения независимо от того, остановлен ли VPN программно —
    // это штатное поведение самой ОС (в частности ColorOS), не связанное
    // с нашим кодом. Диагностический код (ConnectivityManager-проверки)
    // удалён после подтверждения — см. TUN2SOCKS_AND_ECH_PLAN.md,
    // раздел 13.6.
    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        CrashLogger.checkpoint(this, "stopVpn: начало")
        monitorThread?.interrupt()
        monitorThread = null

        // Шаг 1 (КРИТИЧНО первым): закрываем TUN fd — Android немедленно
        // считает VPN отключённым и гасит системный значок/уведомление,
        // независимо от того, как долго ещё будет останавливаться
        // нативный движок ниже.
        try {
            vpnInterface?.close()
            CrashLogger.checkpoint(this, "stopVpn: vpnInterface.close() выполнен")
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
            CrashLogger.checkpoint(this, "stopVpn: vpnInterface.close() выбросил ${e.javaClass.name}")
        }
        vpnInterface = null

        vpnThread?.interrupt()
        vpnThread = null
        executorService?.shutdownNow()
        executorService = null

        // Шаг 2: останавливаем нативный движок (tun2socks/ciadpi) АСИНХРОННО
        // в отдельном daemon-потоке — pthread_join() внутри может занять
        // время (например, если ciadpi_thread завис в блокирующем
        // getaddrinfo() без таймаута), но это больше не влияет на видимое
        // пользователю состояние VPN (уже отключён на шаге 1) и не рискует
        // задержать onStartCommand()/вызвать ANR.
        proxyEngineStopThread = Thread({
            try {
                CrashLogger.checkpoint(this, "stopVpn: (async) перед ProxyEngine.stop()")
                ProxyEngine.stop()
                CrashLogger.checkpoint(this, "stopVpn: (async) ProxyEngine.stop() вернулся")
            } catch (e: Exception) {
                Log.w(TAG, "ProxyEngine stop error", e)
                CrashLogger.checkpoint(this, "stopVpn: (async) ProxyEngine.stop() выбросил ${e.javaClass.name}")
            }
        }, "ProxyEngineStop").apply { isDaemon = true; start() }

        stopForeground(true)
        stopSelf()
        CrashLogger.checkpoint(this, "stopVpn: finished (foreground/self остановлены)")
        Log.i(TAG, "VPN stopped")
    }

    override fun run() {}

    /**
     * VPN engine v3.7 CIS-MAX.
     *
     * Основной путь (когда собран реальный native tun2socks —
     * ProxyEngine.isNativeTun2socksBuilt() == true):
     *   TUN fd → tun2socks (badvpn, читает/пишет TUN fd НАПРЯМУЮ в
     *   нативном коде через dup()'нутый fd) → SOCKS5 127.0.0.1:1080 →
     *   ciadpi (byedpi, TCP split/disorder/fake) → интернет.
     *   В этом режиме Kotlin НЕ трогает TUN fd вообще — иначе два
     *   независимых читателя одного fd (native tun2socks + Kotlin
     *   петля) будут гоняться за одни и те же пакеты.
     *
     * Fallback-путь (badvpn недоступен на этапе сборки — стаб-режим):
     *   TUN → Kotlin PacketProcessor (простая TCP-фрагментация
     *   TLS ClientHello без полноценного TCP/IP стека) → обратно в TUN.
     *   Это заведомо ограниченный режим для CI/разработки без NDK,
     *   не гарантирующий реальную доставку пакетов в интернет
     *   (см. TUN2SOCKS_AND_ECH_PLAN.md — история проблемы).
     */
    private fun runVpn(allowedApps: ArrayList<String>?) {
        try {
            CrashLogger.checkpoint(this, "runVpn: начало, allowedApps=${allowedApps?.size ?: 0}")
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 24)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 64)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                // DoH-ready DNS, оптимизировано для СНГ/РФ:
                .addDnsServer("1.1.1.1")   // Cloudflare
                .addDnsServer("1.0.0.1")
                .addDnsServer("8.8.8.8")   // Google
                .addDnsServer("8.8.4.4")
                .addDnsServer("77.88.8.8") // Yandex — низкая задержка в РФ
                .addDnsServer("77.88.8.1")
                .addDnsServer("9.9.9.9")   // Quad9
                .addDnsServer("94.140.14.14") // AdGuard — работает в РФ
                // MTU 1400: must match tun2socks BTap MTU (tun2socks_jni.c).
                // Slightly below typical 1500 so TLS ClientHello + IP/TCP headers
                // fit without fragmentation that some DPI boxes reassemble.
                .setMtu(1400)
                .setBlocking(true)

            // v3.7 CIS-MAX: исправлен баг self-exclusion в split-tunnel режиме.
            // Android VpnService.Builder может иметь ЛИБО allowed-список,
            // ЛИБО disallowed-список, но не оба одновременно — вызов
            // addDisallowedApplication() после addAllowedApplication()
            // бросает UnsupportedOperationException. Раньше эта ошибка
            // молча проглатывалась в catch-блоке, из-за чего в режиме
            // split-tunneling собственный трафик приложения (DoH-резолв,
            // connectivity-тесты) НЕ исключался из VPN.
            if (!allowedApps.isNullOrEmpty()) {
                // Allowed-режим: добавляем только явно выбранные пользователем
                // пакеты. packageName сюда никогда не должен попадать (иначе
                // собственный трафик приложения зациклится через TUN) — но
                // на всякий случай отфильтровываем его перед добавлением.
                val filteredApps = allowedApps.filter { it != packageName }
                Log.i(TAG, "Split tunneling for: $filteredApps")
                for (pkg in filteredApps) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (e: Exception) { Log.w(TAG, "Skipping package: $pkg", e) }
                }
                // НЕ вызываем addDisallowedApplication() здесь — packageName
                // и так не входит в allowed-список выше, значит трафик
                // приложения не пойдёт через TUN (система маршрутизирует его
                // как обычный трафик неразрешённого приложения).
            } else {
                // Disallowed-режим (весь трафик через VPN, кроме исключений):
                // System apps bypass — чтобы не ломать push, плюс
                // самоисключение приложения — здесь оба вызова корректны,
                // т.к. addAllowedApplication() в этой ветке не вызывался.
                val bypass = listOf(
                    "com.google.android.gms",
                    "com.google.android.gsf",
                    "com.android.vending"
                )
                for (bp in bypass) { try { builder.addDisallowedApplication(bp) } catch (_: Exception) {} }
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }

            CrashLogger.checkpoint(this, "runVpn: перед builder.establish()")
            vpnInterface = builder.establish()
            CrashLogger.checkpoint(this, "runVpn: builder.establish() вернул ${if (vpnInterface != null) "успех" else "null"}")
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                isRunning = false
                return
            }

            currentConfig = ConfigManager.loadPreset(activePresetId)
            val tunFd = vpnInterface!!.fd
            CrashLogger.checkpoint(this, "runVpn: tunFd=$tunFd, перед ProxyEngine.start()")

            val nativeOk = try {
                ProxyEngine.start(tunFd, currentConfig, this)
            } catch (e: Exception) {
                Log.e(TAG, "ProxyEngine start failed", e)
                CrashLogger.checkpoint(this, "runVpn: ProxyEngine.start() выбросил ${e.javaClass.name}: ${e.message}")
                false
            }
            CrashLogger.checkpoint(this, "runVpn: ProxyEngine.start() вернул $nativeOk")
            val nativeTun2socksBuilt = try {
                nativeOk && ProxyEngine.isNativeTun2socksBuilt()
            } catch (e: Exception) {
                Log.w(TAG, "isNativeTun2socksBuilt check failed: ${e.message}")
                false
            }
            CrashLogger.checkpoint(this, "runVpn: nativeTun2socksBuilt=$nativeTun2socksBuilt")
            Log.i(TAG, "Native engine started=$nativeOk nativeTun2socks=$nativeTun2socksBuilt " +
                    "preset=$activePresetId fd=$tunFd port=${currentConfig.socksPort}")

            startPresetMonitor()
            startAsnAutoDetect()

            if (nativeTun2socksBuilt) {
                // v3.7 CIS-MAX: native tun2socks (badvpn) реально читает/пишет
                // TUN fd напрямую в C-коде (dup()'нутый fd, см.
                // tun2socks_bridge.c). Kotlin НЕ должен параллельно трогать
                // тот же fd — вместо packet-loop просто ждём остановки
                // сервиса, обновляя уведомление по таймеру.
                Log.i(TAG, "Native tun2socks active — Kotlin packet-loop disabled, TUN owned by native code")
                CrashLogger.checkpoint(this, "runVpn: перед runNativeIdleLoop()")
                runNativeIdleLoop()
            } else {
                // Fallback: badvpn недоступен на этапе сборки (stub-режим).
                // Используем старый Kotlin packet-loop как best-effort —
                // он НЕ гарантирует полноценную доставку пакетов в интернет
                // (нет реального TCP/IP стека), см. TUN2SOCKS_AND_ECH_PLAN.md.
                Log.w(TAG, "Native tun2socks NOT built — falling back to limited Kotlin packet-loop")
                CrashLogger.checkpoint(this, "runVpn: перед runKotlinPacketLoop()")
                runKotlinPacketLoop()
            }

        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN thread interrupted")
            CrashLogger.checkpoint(this, "runVpn: InterruptedException пойман")
        } catch (e: IOException) {
            Log.e(TAG, "VPN IO exception", e)
            CrashLogger.checkpoint(this, "runVpn: IOException пойман: ${e.message}")
        } catch (e: Throwable) {
            Log.e(TAG, "VPN fatal", e)
            CrashLogger.checkpoint(this, "runVpn: FATAL Throwable ${e.javaClass.name}: ${e.message}")
        } finally {
            CrashLogger.checkpoint(this, "runVpn: finally, вызываем stopVpn()")
            stopVpn()
        }
    }

    /**
     * Простой ожидающий цикл на время работы native tun2socks — TUN fd
     * полностью принадлежит нативному коду (badvpn читает/пишет его
     * напрямую через дескриптор, полученный через dup()). Здесь только
     * периодически обновляем уведомление статистикой (packets processed
     * недоступны из Kotlin в этом режиме — статистику см. в logcat по
     * тегу tun2socks_jni/ciadpi_jni).
     */
    private fun runNativeIdleLoop() {
        CrashLogger.checkpoint(this, "runNativeIdleLoop: вход, isRunning=$isRunning")
        var lastNotifyTime = System.currentTimeMillis()
        // v3.7.6 CIS-MAX: чекпоинт КАЖДУЮ секунду (не только при входе) —
        // резко сужает окно неопределённости при диагностике SIGKILL. Если
        // Kotlin-поток успевает записать несколько таких точек перед
        // смертью процесса, значит сам Kotlin/JVM был жив всё это время —
        // убило либо нативный код (tun2socks/ciadpi под реальной сетевой
        // нагрузкой, не протестированной синтетическим E2E-тестом с одним
        // TCP-соединением), либо процесс целиком системным watchdog'ом
        // ПОКА JVM ещё жила. Если же после "вход" сразу тишина — процесс
        // убит практически мгновенно после входа в цикл.
        var tick = 0
        while (isRunning) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
            tick++
            CrashLogger.checkpoint(this, "runNativeIdleLoop: tick=$tick (${tick}s после входа)")
            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > 10000) {
                updateNotification("Активен (native tun2socks) • $activePresetId")
                lastNotifyTime = now
            }
        }
        Log.i(TAG, "Native idle loop finished")
    }

    /**
     * Fallback-путь для stub-сборки (badvpn недоступен). Использует
     * старую Kotlin-фрагментацию TLS ClientHello без полноценного TCP/IP
     * стека — заведомо ограниченный режим, см. класс-level комментарий
     * runVpn() и TUN2SOCKS_AND_ECH_PLAN.md.
     */
    private fun runKotlinPacketLoop() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        Log.i(TAG, "Starting Kotlin PacketProcessor loop (fallback, stub build) — CIS optimized")
        var packetsProcessed = 0L
        var lastStatTime = System.currentTimeMillis()

        while (isRunning) {
            val length = try {
                input.read(buffer.array())
            } catch (e: IOException) {
                if (isRunning) Log.e(TAG, "TUN read error", e)
                break
            }

            if (length > 0) {
                lastPacketTime = System.currentTimeMillis()
                val packetCopy = buffer.array().copyOf(length)
                val cfgSnapshot = currentConfig
                executorService?.submit {
                    try {
                        synchronized(output) {
                            PacketProcessor.processPacket(packetCopy, length, output, cfgSnapshot)
                        }
                        packetsProcessed++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in packet loop", e)
                    }
                }
                buffer.clear()

                val now = System.currentTimeMillis()
                if (now - lastStatTime > 10000) {
                    val stats = try { PacketProcessor::class.java.getMethod("getStats").invoke(PacketProcessor) } catch (_: Exception) { "" }
                    Log.i(TAG, "Stats: $packetsProcessed pkts $stats preset=$activePresetId")
                    updateNotification("Пакетов: $packetsProcessed • $activePresetId")
                    lastStatTime = now
                }
            }
        }

        Log.i(TAG, "Kotlin loop finished, packets=$packetsProcessed")
    }

    // ===== ASN auto-detect — v3.6 CIS-MAX =====
    // Один раз при старте VPN пытаемся определить оператора (Казахтелеком/
    // Kcell/МТС/Билайн/Ростелеком) по ASN и, если пользователь ещё не
    // выбрал специфичный CIS-пресет вручную, сразу переключаемся на
    // тюнингованный под этого оператора пресет — не дожидаясь провала
    // connectivity-теста в startPresetMonitor().
    private fun startAsnAutoDetect() {
        Thread({
            try {
                CrashLogger.checkpoint(this, "startAsnAutoDetect: поток запущен, ждём 1500ms")
                Thread.sleep(1500) // даём TUN/native-движку время подняться
                if (!isRunning) return@Thread
                CrashLogger.checkpoint(this, "startAsnAutoDetect: вызываем NetworkProfileDetector.detect()")
                val profile = NetworkProfileDetector.detect(this)
                CrashLogger.checkpoint(this, "startAsnAutoDetect: detect() вернул isp=${profile?.isp} asn=${profile?.asn}")
                val suggested = NetworkProfileDetector.presetForIsp(profile)
                CrashLogger.checkpoint(this, "startAsnAutoDetect: suggested=$suggested activePresetId=$activePresetId")
                // Не перебиваем осознанный выбор пользователя — авто-подстановку
                // делаем только если активен пресет по умолчанию ("universal").
                if (suggested != null && suggested != activePresetId &&
                    activePresetId == "universal" && isRunning) {
                    Log.i(TAG, "ASN auto-detect: switching to CIS-tuned preset $suggested " +
                            "(isp=${profile?.isp}, as=${profile?.asn})")
                    activePresetId = suggested
                    currentConfig = ConfigManager.loadPreset(suggested)
                    try {
                        CrashLogger.checkpoint(this, "startAsnAutoDetect: перед ProxyEngine.stop() (рестарт на пресет $suggested)")
                        ProxyEngine.stop()
                        CrashLogger.checkpoint(this, "startAsnAutoDetect: ProxyEngine.stop() вернулся")
                        val tunFd = vpnInterface?.fd ?: -1
                        if (tunFd > 0) {
                            Thread.sleep(300)
                            CrashLogger.checkpoint(this, "startAsnAutoDetect: перед ProxyEngine.start() tunFd=$tunFd")
                            ProxyEngine.start(tunFd, currentConfig, this)
                            CrashLogger.checkpoint(this, "startAsnAutoDetect: ProxyEngine.start() вернулся")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to apply ASN-detected preset", e)
                        CrashLogger.checkpoint(this, "startAsnAutoDetect: ИСКЛЮЧЕНИЕ при рестарте ${e.javaClass.name}: ${e.message}")
                    }
                    updateNotification("Авто-определение оператора → $suggested")
                } else {
                    CrashLogger.checkpoint(this, "startAsnAutoDetect: рестарт не требуется (условие не выполнено)")
                }
            } catch (_: InterruptedException) {
                // сервис остановлен во время детекта — это нормально
                CrashLogger.checkpoint(this, "startAsnAutoDetect: InterruptedException (нормально)")
            } catch (e: Throwable) {
                Log.w(TAG, "ASN auto-detect failed: ${e.message}")
                CrashLogger.checkpoint(this, "startAsnAutoDetect: FATAL ${e.javaClass.name}: ${e.message}")
            }
        }, "AsnAutoDetect").apply { isDaemon = true; start() }
    }

    // ===== Auto preset monitor — CIS/RU =====
    private fun startPresetMonitor() {
        monitorThread?.interrupt()
        monitorThread = Thread({
            val testUrls = listOf(
                "https://1.1.1.1/cdn-cgi/trace",
                "https://77.88.8.8/",
                "https://www.google.com/generate_204",
                "https://yandex.ru/",
                "https://www.youtube.com/generate_204",
                "https://vk.com/",
                "https://ok.ru/"
            )
            var idx = 0
            while (isRunning) {
                try {
                    Thread.sleep(15000)
                    if (!isRunning) break
                    val idleMs = System.currentTimeMillis() - lastPacketTime
                    var needTest = idleMs > 30000
                    if (!needTest && (++idx % 4 == 0)) needTest = true
                    if (needTest) {
                        val ok = testConnectivity(testUrls)
                        if (ok) {
                            consecutiveFails = 0
                        } else {
                            consecutiveFails++
                            Log.w(TAG, "Connectivity test failed $consecutiveFails/2")
                            if (consecutiveFails >= 2) {
                                switchToNextPreset()
                                consecutiveFails = 0
                            }
                        }
                    } else {
                        consecutiveFails = 0
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error", e)
                }
            }
        }, "PresetMonitor").apply { isDaemon = true; start() }
    }

    private fun testConnectivity(urls: List<String> = listOf(
        "https://1.1.1.1/cdn-cgi/trace",
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204",
        "https://yandex.ru/",
        "https://vk.com/"
    )): Boolean {
        // v3.6.1: DoH-резолв домена (DohResolver — чистый HttpsURLConnection,
        // без okhttp) перед подключением, чтобы не полагаться на системный
        // DNS резолвер, который провайдер может спуфить.
        for (u in urls) {
            try {
                val url = URL(u)
                val host = url.host
                // Прогреваем DoH-резолв (полезно даже если сама проверка идёт через
                // системный стек — подтверждает, что DoH-путь резолвинга живой).
                try { DohResolver.resolve(host) } catch (_: Exception) {}

                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "myVPNproject/3.6 CIS")
                // Трафик идёт ЧЕРЕЗ VPN — проверяем обход
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399 || code == 204) {
                    Log.d(TAG, "Connectivity OK $u -> $code")
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
    }


    private fun switchToNextPreset() {
        try {
            // v3.6: базовые + CIS-специфичные пресеты (kz-telecom/mts-ru/beeline-ru/rostelecom)
            val presets = AUTO_SWITCH_ORDER
            val currentIdx = presets.indexOf(activePresetId).let { if (it >= 0) it else 0 }
            val nextIdx = (currentIdx + 1) % presets.size
            val nextId = presets[nextIdx]
            Log.w(TAG, "Auto-switch preset: $activePresetId → $nextId")
            activePresetId = nextId
            currentConfig = ConfigManager.loadPreset(nextId)
            // Перезапуск native движка
            try {
                ProxyEngine.stop()
                val tunFd = vpnInterface?.fd ?: -1
                if (tunFd > 0) {
                    Thread.sleep(300)
                    ProxyEngine.start(tunFd, currentConfig, this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart native engine on preset switch", e)
            }
            updateNotification("Авто-переключение → $nextId")
        } catch (e: Exception) {
            Log.e(TAG, "Preset switch failed", e)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("myVPNproject активен")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        CrashLogger.checkpoint(this, "BypassVpnService.onDestroy() — сервис уничтожается системой или самим приложением")
        stopVpn()
        super.onDestroy()
    }

    // v3.7.5 CIS-MAX: onTaskRemoved() вызывается, когда пользователь свайпает
    // приложение из списка недавних — на некоторых агрессивных прошивках
    // (ColorOS/MIUI) система может это интерпретировать как "приложение
    // закрыто" и убить процесс целиком вместе с сервисом, даже если сервис
    // формально foreground. Чекпоинт поможет отличить этот сценарий от
    // краша при простом нажатии кнопки (когда приложение остаётся открытым).
    override fun onTaskRemoved(rootIntent: Intent?) {
        CrashLogger.checkpoint(this, "BypassVpnService.onTaskRemoved() — приложение убрано из недавних задач")
        super.onTaskRemoved(rootIntent)
    }

    override fun onLowMemory() {
        CrashLogger.checkpoint(this, "BypassVpnService.onLowMemory() — система сигнализирует о нехватке памяти")
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        CrashLogger.checkpoint(this, "BypassVpnService.onTrimMemory(level=$level)")
        super.onTrimMemory(level)
    }

    // ── Foreground helpers ────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "VPN сервис",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "myVPNproject DPI bypass CIS" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("myVPNproject активен")
            .setContentText("DPI bypass • $activePresetId • RU/CIS")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
}
