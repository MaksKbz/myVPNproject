package com.makskbz.myvpnproject.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.makskbz.myvpnproject.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BypassVpnService v3.0
 *
 * Новая архитектура (Path B):
 *   TUN (VpnService) → tun2socks (C) → ciadpi SOCKS5 (C) → интернет
 *
 * Kotlin-сервис теперь занимается только:
 * 1. Созданием TUN-интерфейса через Android VpnService.Builder
 * 2. Запуском ProxyEngine (ciadpi + tun2socks) через JNI
 * 3. Управлением foreground notification (обязательно для Android 8+)
 *
 * Никакой самописной обработки пакетов больше нет — всё делает ciadpi.
 */
class BypassVpnService : VpnService() {

    companion object {
        const val TAG = "BypassVpnService"
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val EXTRA_ALLOWED_APPS = "ALLOWED_APPS"
        const val EXTRA_PRESET_ID    = "PRESET_ID"

        private const val NOTIFICATION_ID      = 1001
        private const val NOTIFICATION_CHANNEL = "vpn_service_channel"

        // MTU для TUN-интерфейса
        // Примечание: в архитектуре tun2socks+ciadpi MTU 1500 корректен,
        // так как ciadpi сам управляет реальным MTU исходящих соединений.
        private const val TUN_MTU = 1500
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)
                val presetId    = intent.getStringExtra(EXTRA_PRESET_ID) ?: "universal"
                startVpn(allowedApps, presetId)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(allowedApps: ArrayList<String>?, presetId: String) {
        if (isRunning.getAndSet(true)) return

        // 1. Показываем foreground notification (обязательно для Android 8+)
        startForeground(NOTIFICATION_ID, buildNotification())

        // 2. Загружаем конфигурацию по выбранному пресету
        val config = ConfigManager.loadPreset(presetId).copy(
            allowedApps = allowedApps ?: emptyList()
        )
        Log.i(TAG, "Starting VPN v3.0 with preset='$presetId', apps=${config.allowedApps.size}")

        // 3. Строим TUN-интерфейс
        val builder = Builder()
            .setSession("myVPNproject v3.0")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)          // Перехватываем весь трафик
            .addDnsServer("1.1.1.1")          // Cloudflare DoH-ready DNS
            .addDnsServer("8.8.8.8")          // Google DNS (резервный)
            .setMtu(TUN_MTU)

        // Split tunneling: если список пустой — заворачиваем всё
        if (config.allowedApps.isNotEmpty()) {
            for (pkg in config.allowedApps) {
                try {
                    builder.addAllowedApplication(pkg)
                    Log.d(TAG, "Split tunneling: added $pkg")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot add app to tunnel: $pkg")
                }
            }
        }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            Log.e(TAG, "Failed to establish TUN interface")
            stopVpn()
            return
        }

        // 4. Запускаем ciadpi SOCKS5-движок
        ProxyEngine.startProxy(config)

        // 5. Запускаем tun2socks мост (TUN fd → SOCKS5:1080)
        // detachFd() передаёт владение дескриптором в нативный код
        val tunFd = tunInterface!!.detachFd()
        ProxyEngine.startBridge(tunFd, config.socksPort)

        Log.i(TAG, "VPN v3.0 started: TUN established, engine running")
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping VPN v3.0")

        // Останавливаем движки перед закрытием TUN
        ProxyEngine.stop()

        try { tunInterface?.close() } catch (e: Exception) { Log.e(TAG, "Error closing TUN", e) }
        tunInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN v3.0 stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ===========================================================================
    // Foreground Notification
    // ===========================================================================

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BypassVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("myVPNproject — DPI Bypass")
            .setContentText("VPN активен. Обход DPI включён.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Остановить", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о работе DPI bypass VPN"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
