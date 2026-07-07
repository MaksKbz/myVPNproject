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
import java.io.IOException

class BypassVpnService : VpnService() {

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
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            if (!allowedApps.isNullOrEmpty()) {
                Log.i(TAG, "Split tunneling for: $allowedApps")
                for (pkg in allowedApps) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (e: Exception) { Log.w(TAG, "Skipping package: $pkg", e) }
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                isRunning = false
                return
            }

            val config = ConfigManager.loadPreset(activePresetId)
            val tunFd  = vpnInterface!!.fd

            // Запускаем нативный стек:
            //   TUN(fd=$tunFd) → tun2socks → SOCKS5:${config.socksPort} → ciadpi → интернет
            val ok = ProxyEngine.start(tunFd, config)
            Log.i(TAG, "Native engine started=$ok preset=$activePresetId fd=$tunFd")

        } catch (e: IOException) {
            Log.e(TAG, "VPN setup error", e)
            isRunning = false
        }
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        ProxyEngine.stop()
        try { vpnInterface?.close() } catch (e: Exception) { Log.e(TAG, "Close error", e) }
        vpnInterface = null
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "VPN stopped")
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
