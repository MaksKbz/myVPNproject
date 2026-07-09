package com.makskbz.myvpnproject.vpn

import android.util.Log

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
     * Запускает полный нативный стек:
     *   1. ciadpi — SOCKS5-прокси с DPI-обходом на порту config.socksPort
     *   2. tun2socks — читает пакеты из TUN fd напрямую и форвардит в SOCKS5
     *
     * @param tunFd  файловый дескриптор TUN-интерфейса (из VpnService.Builder.establish())
     * @param config конфигурация пресета
     * @return true если запуск прошёл без ошибок на уровне JNI-вызовов
     *         (не гарантирует, что tun2socks реально поднялся — см.
     *         isNativeForwardingActive() для проверки после небольшой задержки)
     */
    fun start(tunFd: Int, config: BypassConfig): Boolean {
        if (!nativeLibsLoaded) {
            Log.e(TAG, "Native libraries not loaded — cannot start native engine")
            return false
        }

        Log.i(TAG, "Starting native engine: preset=${config.presetName} port=${config.socksPort}")

        // Шаг 1: запускаем ciadpi — DPI-обход через SOCKS5
        val ciadpiResult = ciadpiStart(
            socksPort   = config.socksPort,
            splitPos    = config.splitPosition?.filter { it.isDigit() }?.toIntOrNull() ?: 1,
            disorder    = if (config.disorderPosition != null) 1 else 0,
            fakeEnabled = config.fakeEnabled,
            fakeTtl     = config.fakeTtl,
            dropSack    = config.dropSack,
            autoMode    = config.autoMode ?: ""
        )
        if (ciadpiResult < 0) {
            Log.e(TAG, "ciadpi failed to start (rc=$ciadpiResult)")
            return false
        }

        // Шаг 2: запускаем tun2socks — форвардинг TUN → SOCKS5.
        // tun2socksStart() сам порождает поток и не блокирует вызывающий —
        // реальный успех/провал event loop'а внутри badvpn виден только
        // по логам (LOGCAT tag=tun2socks_jni) или по факту, что реальный
        // трафик начинает идти (см. isNativeForwardingActive()).
        val tun2socksResult = tun2socksStart(
            tunFd      = tunFd,
            socksPort  = config.socksPort,
            tunAddr    = "10.0.0.2",
            tunGw      = "10.0.0.1",
            tunPrefix  = 24
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
     * (не stub-режим) — т.е. tun2socks действительно форвардит пакеты,
     * а не крутит пустой цикл. Используется BypassVpnService, чтобы
     * решить, нужен ли ещё Kotlin packet-loop как fallback.
     *
     * ПРИМЕЧАНИЕ: это компайл-тайм признак (BADVPN_AVAILABLE в
     * CMakeLists.txt), а не runtime-проверка живости event loop —
     * последнее видно только по логам/трафику.
     */
    external fun isNativeTun2socksBuilt(): Boolean

    /**
     * Останавливает tun2socks, затем ciadpi.
     */
    fun stop() {
        if (!nativeLibsLoaded) return
        Log.i(TAG, "Stopping native engine")
        try { tun2socksStop() } catch (e: Exception) { Log.w(TAG, "tun2socksStop error", e) }
        try { ciadpiStop() } catch (e: Exception) { Log.w(TAG, "ciadpiStop error", e) }
        Log.i(TAG, "Native engine stopped")
    }

    // ── JNI-объявления: ciadpi ────────────────────────────────────────

    private external fun ciadpiStart(
        socksPort:   Int,
        splitPos:    Int,
        disorder:    Int,
        fakeEnabled: Boolean,
        fakeTtl:     Int,
        dropSack:    Boolean,
        autoMode:    String
    ): Int

    private external fun ciadpiStop()

    @Suppress("unused")
    private external fun ciadpiVersion(): String

    // ── JNI-объявления: tun2socks ─────────────────────────────────────

    private external fun tun2socksStart(
        tunFd:     Int,
        socksPort: Int,
        tunAddr:   String,
        tunGw:     String,
        tunPrefix: Int
    ): Int

    private external fun tun2socksStop()
}

