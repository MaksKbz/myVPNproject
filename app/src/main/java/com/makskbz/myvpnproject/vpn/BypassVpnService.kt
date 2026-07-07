package com.makskbz.myvpnproject.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
    @Volatile private var isRunning = false
    private var activePresetId: String = "universal"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                activePresetId = intent.getStringExtra(EXTRA_PRESET_ID) ?: "universal"
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)
                startVpn(allowedApps)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(allowedApps: ArrayList<String>?) {
        if (isRunning) return
        isRunning = true
        // Foreground Service — обязательно для Android 8+
        startForeground(NOTIFICATION_ID, buildNotification())
        executorService = Executors.newCachedThreadPool()
        vpnThread = Thread({ runVpn(allowedApps) }, "BypassVpnThread").apply { start() }
        Log.i(TAG, "VPN service v3.4-hybrid started. Preset: $activePresetId")
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        // Сначала останавливаем нативный движок
        try { ProxyEngine.stop() } catch (e: Exception) { Log.w(TAG, "ProxyEngine stop error", e) }
        try { vpnInterface?.close() } catch (e: Exception) { Log.e(TAG, "Close error", e) }
        vpnInterface = null
        vpnThread?.interrupt()
        vpnThread = null
        executorService?.shutdownNow()
        executorService = null
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    override fun run() {
        // VpnService.Runnable — не используется, runVpn() вызывается напрямую
    }

    /**
     * Основной цикл VPN: поднимаем TUN, запускаем native ProxyEngine (ciadpi),
     * затем fallback Kotlin PacketProcessor для обхода DPI, пока tun2socks stub.
     * Это гибридный режим v3.4: нативный SOCKS5 работает, а трафик из TUN
     * обрабатывается Kotlin-движком, т.к. badvpn/tun2socks ещё не подключён.
     */
    private fun runVpn(allowedApps: ArrayList<String>?) {
        try {
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setAddDisallowedApplication(false)
                .setMtu(1400) // 1400 безопаснее для фрагментации, чем 1500

            if (!allowedApps.isNullOrEmpty()) {
                Log.i(TAG, "Split tunneling for: $allowedApps")
                for (pkg in allowedApps) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (e: Exception) { Log.w(TAG, "Skipping package: $pkg", e) }
                }
            } else {
                // Разрешаем браузеры по умолчанию, остальное через VPN
                try {
                    builder.addAllowedApplication("com.android.chrome")
                    builder.addAllowedApplication("com.opera.browser")
                    builder.addAllowedApplication("org.mozilla.firefox")
                    builder.addAllowedApplication("com.telegram.messenger")
                } catch (_: Exception) { /* ignore */ }
            }

            // Всегда исключаем сам VPN, чтобы избежать петли
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                isRunning = false
                return
            }

            val config = ConfigManager.loadPreset(activePresetId)
            val tunFd = vpnInterface!!.fd

            // Шаг 1 — нативный ciadpi SOCKS5
            val nativeOk = try {
                ProxyEngine.start(tunFd, config)
            } catch (e: Exception) {
                Log.e(TAG, "ProxyEngine start failed", e)
                false
            }
            Log.i(TAG, "Native engine started=$nativeOk preset=$activePresetId fd=$tunFd port=${config.socksPort}")

            // Шаг 2 — Kotlin TUN loop (fallback, пока tun2socks = stub)
            // Это обеспечивает реальный обход блокировок уже сейчас.
            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            Log.i(TAG, "Starting Kotlin PacketProcessor loop (hybrid mode)")
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
                    // Копия пакета — защита от race condition между потоками
                    val packetCopy = buffer.array().copyOf(length)
                    executorService?.submit {
                        try {
                            // Синхронизация output — защита от перемешивания фрагментов
                            synchronized(output) {
                                PacketProcessor.processPacket(packetCopy, length, output, config)
                            }
                            packetsProcessed++
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in packet loop", e)
                        }
                    }
                    buffer.clear()

                    // Статистика раз в 10 сек
                    val now = System.currentTimeMillis()
                    if (now - lastStatTime > 10000) {
                        Log.i(TAG, "Stats: $packetsProcessed packets processed, preset=$activePresetId")
                        lastStatTime = now
                    }
                }
                // Thread.sleep удалён — input.read() блокирующий
            }

            Log.i(TAG, "Kotlin loop finished, packets=$packetsProcessed")

        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN thread interrupted")
        } catch (e: IOException) {
            Log.e(TAG, "VPN IO exception", e)
        } catch (e: Exception) {
            Log.e(TAG, "VPN fatal", e)
        } finally {
            stopVpn()
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ── Foreground helpers ────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "VPN сервис",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "myVPNproject DPI bypass" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("myVPNproject активен")
            .setContentText("DPI bypass работает • пресет: $activePresetId")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
}
