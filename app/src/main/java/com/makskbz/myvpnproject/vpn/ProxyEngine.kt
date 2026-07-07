package com.makskbz.myvpnproject.vpn

import android.util.Log

/**
 * ProxyEngine — Kotlin-мост между BypassVpnService и нативными библиотеками.
 *
 * Архитектура:
 *   TUN fd → tun2socks (C) → SOCKS5 127.0.0.1:1080 → ciadpi (C) → интернет
 *
 * Жизненный цикл:
 *   1. BypassVpnService.startVpn() вызывает ProxyEngine.start(tunFd, config)
 *   2. ProxyEngine запускает ciadpi (SOCKS5-прокси с DPI-обходом)
 *   3. ProxyEngine запускает tun2socks (форвардинг TUN → SOCKS5)
 *   4. При остановке — обратный порядок: tun2socks → ciadpi
 *
 * Статус stub: сборка проходит без submodule-ов.
 *   После `git submodule add` раскомментируйте вызовы в C-файлах.
 */
object ProxyEngine {

    private const val TAG = "ProxyEngine"

    init {
        try {
            System.loadLibrary("ciadpi_jni")
            System.loadLibrary("tun2socks_jni")
            Log.i(TAG, "Native libraries loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            // На этапе stub-сборки без submodule — нормально.
            // После добавления byedpi/badvpn эта ошибка исчезнет.
            Log.w(TAG, "Native libs not available (stub mode): ${e.message}")
        }
    }

    /**
     * Запускает полный нативный стек:
     *   1. ciadpi — SOCKS5-прокси с DPI-обходом на порту config.socksPort
     *   2. tun2socks — читает пакеты из TUN fd и форвардит в SOCKS5
     *
     * @param tunFd  файловый дескриптор TUN-интерфейса (из VpnService.Builder.establish())
     * @param config конфигурация пресета
     * @return true если запуск прошёл без ошибок
     */
    fun start(tunFd: Int, config: BypassConfig): Boolean {
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
            Log.e(TAG, "ciadpi failed to start (rc=$ciadpiResult) — stub mode active")
            // В stub-режиме продолжаем — реальная ошибка только после submodule
        }

        // Шаг 2: запускаем tun2socks — форвардинг TUN → SOCKS5
        val tun2socksResult = tun2socksStart(
            tunFd      = tunFd,
            socksPort  = config.socksPort,
            tunAddr    = "10.0.0.2",
            tunGw      = "10.0.0.1",
            tunPrefix  = 24
        )
        if (tun2socksResult < 0) {
            Log.e(TAG, "tun2socks failed to start (rc=$tun2socksResult) — stub mode active")
        }

        Log.i(TAG, "Native engine started (stub). Add submodules to activate real DPI bypass.")
        return true
    }

    /**
     * Останавливает tun2socks, затем ciadpi.
     */
    fun stop() {
        Log.i(TAG, "Stopping native engine")
        tun2socksStop()
        ciadpiStop()
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
