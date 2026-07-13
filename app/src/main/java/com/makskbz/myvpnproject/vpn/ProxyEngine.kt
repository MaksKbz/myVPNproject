package com.makskbz.myvpnproject.vpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import java.io.File

/**
 * ProxyEngine — Kotlin-мост между BypassVpnService и нативными библиотеками.
 *
 * Архитектура (v3.7 CIS-MAX — tun2socks больше не stub):
 *   TUN fd → tun2socks (badvpn, C, JNI) → SOCKS5 127.0.0.1:1080 → ciadpi (byedpi, C, JNI) → интернет
 *
 * Жизненный цикл:
 *   1. BypassVpnService.startVpn() вызывает ProxyEngine.start(tunFd, config)
 *   2. ProxyEngine запускает ciadpi (SOCKS5-прокси с DPI-обходом)
 *   3. ProxyEngine запускает tun2socks (полноценный форвардинг TUN → SOCKS5,
 *      включая TCP через lwIP-стек и UDP через SOCKS5 UDP ASSOCIATE)
 *   4. При остановке — обратный порядок: tun2socks → ciadpi
 *
 * ВАЖНО: когда native tun2socks реально запущен (isNativeForwardingActive()
 * возвращает true), он сам читает/пишет TUN fd напрямую в нативном коде
 * (через dup() внутри BTap_Init2, см. tun2socks_bridge.c). В этом случае
 * BypassVpnService НЕ должен параллельно читать тот же TUN fd через
 * Kotlin FileInputStream/FileOutputStream — это гонка за пакеты между
 * двумя независимыми читателями одного fd. Kotlin packet-loop
 * (PacketProcessor/DnsInterceptor) остаётся только как fallback для
 * stub-сборки (когда badvpn недоступен на этапе компиляции).
 */
object ProxyEngine {

    private const val TAG = "ProxyEngine"

    @Volatile private var nativeLibsLoaded = false

    init {
        try {
            System.loadLibrary("ciadpi_jni")
            System.loadLibrary("tun2socks_jni")
            nativeLibsLoaded = true
            Log.i(TAG, "Native libraries loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native libs not available: ${e.message}")
        }
    }

    /**
     * v3.7.4 CIS-MAX: устанавливает нативный обработчик крашей (SIGABRT/
     * SIGSEGV/...), пишущий диагностику в файл внутри filesDir приложения.
     */
    fun installCrashHandler(context: Context) {
        if (!nativeLibsLoaded) return
        val path = CrashLogger.nativeCrashLogPath(context)
        try {
            installCrashHandler(path)
            Log.i(TAG, "Native crash handler installed (ciadpi_jni.so)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install native crash handler (ciadpi_jni.so): ${e.message}")
        }
        try {
            installCrashHandlerTun2socks(path)
            Log.i(TAG, "Native crash handler installed (tun2socks_jni.so)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install native crash handler (tun2socks_jni.so): ${e.message}")
        }
    }

    /**
     * Запускает полный нативный стек:
     *   1. ciadpi — SOCKS5-прокси с DPI-обходом на порту config.socksPort
     *   2. tun2socks — читает пакеты из TUN fd напрямую и форвардит в SOCKS5
     *
     * v3.7.14: пробрасывает ПОЛНЫЙ набор desync-параметров пресета (oob,
     * tlsrec, modHttp, udpFake) — раньше JNI игнорировал oob/tlsrec/modHttp,
     * из-за чего пресет "universal" (OOB по SNI) фактически работал как
     * простой disorder@1, а youtube/aggressive теряли TLS-record split.
     * Также передаёт IPv6-адрес netif в tun2socks (раньше IPv6 TCP/UDP
     * из TUN молча дропался lwIP'ом — Chrome Happy-Eyeballs к Cloudflare
     * сайтам вроде meduza.io зависал на мёртвом AAAA-пути).
     *
     * v3.8 SUPER-BYPASS: ДО запуска ciadpi поднимаем ProtectSocketServer
     * (см. ProtectSocketServer.kt) — AF_UNIX сервер, дающий нативному
     * коду возможность вызвать VpnService.protect(fd) на каждом исходящем
     * сокете (byedpi/extend.c: socket_mod()/protect(), уже вендорено,
     * раньше просто не было моста на Kotlin-стороне). Без этого outbound-
     * сокеты ciadpi сами попадали в TUN — петля TUN→SOCKS→ciadpi→TUN,
     * из-за которой сайты либо не открывались, либо открывались через
     * раз, при том что сторонние приложения (ByeDPI) с собственной
     * protect-связкой работали нормально на том же устройстве.
     */
    fun start(tunFd: Int, config: BypassConfig, vpnService: VpnService): Boolean {
        if (!nativeLibsLoaded) {
            Log.e(TAG, "Native libraries not loaded — cannot start native engine")
            return false
        }

        Log.i(TAG, "Starting native engine: preset=${config.presetName} port=${config.socksPort} " +
                "split=${config.splitPosition} disorder=${config.disorderPosition} " +
                "oob=${config.oobPosition} fake=${config.fakeEnabled} tlsrec=${config.tlsRec}")

        // v3.8 SUPER-BYPASS: путь берём из filesDir приложения (доступен на
        // чтение/запись самому процессу, недоступен другим приложениям —
        // как и рекомендовано для AF_UNIX FILESYSTEM-сокетов на Android).
        val protectPath = File(vpnService.filesDir, "protect_path").absolutePath
        val protectServerOk = try {
            ProtectSocketServer.start(protectPath, vpnService)
        } catch (e: Exception) {
            Log.e(TAG, "ProtectSocketServer.start() threw: ${e.message}", e)
            false
        }
        if (!protectServerOk) {
            // Не фатально — движок всё равно запустится (это поведение
            // ДО v3.8), но без protect() возможна петля TUN↔SOCKS для
            // некоторых прошивок. Логируем явно, чтобы это было видно.
            Log.w(TAG, "ProtectSocketServer failed to start — outbound sockets NOT protected, " +
                    "may loop back into TUN on some OEM firmwares")
        }

        // Числовая fallback-позиция split (если строка не распарсится в C).
        val splitPosNum = config.splitPosition?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: config.oobPosition?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: 1
        val disorder = if (config.disorderPosition != null) 1 else 0

        // Шаг 1: запускаем ciadpi — DPI-обход через SOCKS5
        val ciadpiResult = ciadpiStart(
            socksPort     = config.socksPort,
            splitPos      = splitPosNum,
            disorder      = disorder,
            fakeEnabled   = config.fakeEnabled,
            fakeTtl       = config.fakeTtl,
            dropSack      = config.dropSack,
            autoMode      = config.autoMode ?: "",
            splitPosStr   = config.splitPosition ?: config.disorderPosition ?: "1",
            oobPosStr     = config.oobPosition ?: "",
            tlsRecStr     = config.tlsRec ?: "",
            modHttpStr    = config.modHttp ?: "",
            udpFakeCount  = config.udpFakeCount ?: 0,
            protectPath   = if (protectServerOk) protectPath else ""
        )
        if (ciadpiResult < 0) {
            Log.e(TAG, "ciadpi failed to start (rc=$ciadpiResult)")
            return false
        }

        // Шаг 2: запускаем tun2socks — форвардинг TUN → SOCKS5.
        // IPv6 netif: должен быть из той же /64, что addAddress() в
        // BypassVpnService (fd00:1:fd00:1:fd00:1:fd00:1/64) — виртуальный
        // роутер .2, клиент .1. Без этого lwIP дропает все IPv6-пакеты.
        val tun2socksResult = tun2socksStart(
            tunFd      = tunFd,
            socksPort  = config.socksPort,
            tunAddr    = "10.0.0.2",
            tunGw      = "10.0.0.1",
            tunPrefix  = 24,
            tunIp6Addr = "fd00:1:fd00:1:fd00:1:fd00:2"
        )
        if (tun2socksResult < 0) {
            Log.e(TAG, "tun2socks failed to start (rc=$tun2socksResult)")
            try { ciadpiStop() } catch (_: Exception) {}
            return false
        }

        Log.i(TAG, "Native engine started: tun2socks + ciadpi chain active")
        return true
    }

    /**
     * Возвращает true, если приложение собрано с реальным badvpn
     * (не stub-режим) — т.е. tun2socks действительно форвардит пакеты.
     */
    external fun isNativeTun2socksBuilt(): Boolean

    /**
     * Останавливает tun2socks, затем ciadpi, затем ProtectSocketServer.
     */
    fun stop() {
        if (!nativeLibsLoaded) return
        Log.i(TAG, "Stopping native engine")
        try { tun2socksStop() } catch (e: Exception) { Log.w(TAG, "tun2socksStop error", e) }
        try { ciadpiStop() } catch (e: Exception) { Log.w(TAG, "ciadpiStop error", e) }
        try { ProtectSocketServer.stop() } catch (e: Exception) { Log.w(TAG, "ProtectSocketServer stop error", e) }
        Log.i(TAG, "Native engine stopped")
    }

    // ── JNI-объявления: ciadpi ────────────────────────────────────────

    private external fun ciadpiStart(
        socksPort:     Int,
        splitPos:      Int,
        disorder:      Int,
        fakeEnabled:   Boolean,
        fakeTtl:       Int,
        dropSack:      Boolean,
        autoMode:      String,
        splitPosStr:   String,
        oobPosStr:     String,
        tlsRecStr:     String,
        modHttpStr:    String,
        udpFakeCount:  Int,
        protectPath:   String
    ): Int

    private external fun ciadpiStop()

    @Suppress("unused")
    private external fun ciadpiVersion(): String

    private external fun installCrashHandler(logPath: String)

    private external fun installCrashHandlerTun2socks(logPath: String)

    // ── JNI-объявления: tun2socks ─────────────────────────────────────

    private external fun tun2socksStart(
        tunFd:     Int,
        socksPort: Int,
        tunAddr:   String,
        tunGw:     String,
        tunPrefix: Int,
        tunIp6Addr: String
    ): Int

    private external fun tun2socksStop()
}
