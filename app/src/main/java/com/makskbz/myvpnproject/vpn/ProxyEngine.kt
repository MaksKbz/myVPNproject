package com.makskbz.myvpnproject.vpn

import android.util.Log
import kotlinx.coroutines.*

/**
 * ProxyEngine
 *
 * Управляет жизненным циклом двух нативных движков:
 * 1. ciadpi  — SOCKS5-прокси с DPI-манипуляциями (C-библиотека)
 * 2. tun2socks — мост TUN-интерфейс → SOCKS5-прокси
 *
 * Оба движка компилируются через NDK (см. CMakeLists.txt).
 * До подключения git submodule работают как заглушки — приложение
 * не крашится, но DPI-обход неактивен (см. ciadpi_jni.c / tun2socks_jni.c).
 */
object ProxyEngine {

    private const val TAG = "ProxyEngine"

    // Загружаем .so библиотеки при старте
    init {
        try {
            System.loadLibrary("ciadpi")
            Log.i(TAG, "ciadpi library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ciadpi library not found — stub mode active", e)
        }
        try {
            System.loadLibrary("tun2socks")
            Log.i(TAG, "tun2socks library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "tun2socks library not found — stub mode active", e)
        }
    }

    // ----- JNI-методы (реализованы в ciadpi_jni.c / tun2socks_jni.c) -----

    @JvmStatic private external fun startCiadpi(args: Array<String>): Int
    @JvmStatic private external fun stopCiadpi()
    @JvmStatic private external fun startTun2socks(tunFd: Int, socksAddr: String, socksPort: Int): Int
    @JvmStatic private external fun stopTun2socks()

    // ----- Coroutine-обёртки -----

    private var ciadpiJob: Job? = null
    private var tun2socksJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Запускает ciadpi в отдельном IO-потоке.
     * ciadpi_main() блокируется до вызова stop().
     */
    fun startProxy(config: BypassConfig) {
        val args = ConfigManager.toCliArgs(config)
        Log.i(TAG, "Starting ciadpi: ${args.joinToString(" ")}")
        ciadpiJob = scope.launch {
            try {
                startCiadpi(args)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "ciadpi stub: library not linked")
            }
        }
    }

    /**
     * Запускает tun2socks мост.
     * tunFd получается через ParcelFileDescriptor.detachFd().
     */
    fun startBridge(tunFd: Int, socksPort: Int) {
        Log.i(TAG, "Starting tun2socks bridge: fd=$tunFd -> 127.0.0.1:$socksPort")
        tun2socksJob = scope.launch {
            try {
                startTun2socks(tunFd, "127.0.0.1", socksPort)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "tun2socks stub: library not linked")
            }
        }
    }

    /**
     * Останавливает оба движка.
     */
    fun stop() {
        Log.i(TAG, "Stopping proxy engine")
        try { stopCiadpi()   } catch (e: UnsatisfiedLinkError) { /* stub */ }
        try { stopTun2socks() } catch (e: UnsatisfiedLinkError) { /* stub */ }
        ciadpiJob?.cancel()
        tun2socksJob?.cancel()
        ciadpiJob = null
        tun2socksJob = null
    }

    fun isRunning(): Boolean =
        ciadpiJob?.isActive == true || tun2socksJob?.isActive == true
}
