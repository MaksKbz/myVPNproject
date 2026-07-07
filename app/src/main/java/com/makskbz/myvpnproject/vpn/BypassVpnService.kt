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
        const val ACTION_STOP = "STOP"
        const val EXTRA_ALLOWED_APPS = "ALLOWED_APPS"
        const val EXTRA_PRESET_ID = "PRESET_ID"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "vpn_channel"
    }

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    // Исправление #5: @Volatile гарантирует видимость изменений между потокам
    @Volatile private var isRunning = false
    private var executorService: ExecutorService? = null
    private var activePresetId: String = "universal"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)
            // Исправление #3: читаем presetId и передаём в PacketProcessor
            activePresetId = intent.getStringExtra(EXTRA_PRESET_ID) ?: "universal"
            startVpn(allowedApps)
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(allowedApps: ArrayList<String>?) {
        if (isRunning) return
        isRunning = true
        // Исправление #6: Foreground Service — сервис не убьётся системой на Android 8+
        startForeground(NOTIFICATION_ID, buildNotification())
        executorService = Executors.newCachedThreadPool()
        vpnThread = Thread({ runVpn(allowedApps) }, "BypassVpnThread").apply { start() }
        Log.i(TAG, "VPN service 3.2.0 started. Preset: $activePresetId")
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        stopForeground(true)
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        vpnThread?.interrupt()
        vpnThread = null
        executorService?.shutdownNow()
        executorService = null
        stopSelf()
        Log.i(TAG, "VPN service 3.2.0 stopped.")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun run() {}

    private fun runVpn(allowedApps: ArrayList<String>?) {
        try {
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1400)

            if (allowedApps != null && allowedApps.isNotEmpty()) {
                Log.i(TAG, "Applying Split Tunneling for apps: $allowedApps")
                for (packageName in allowedApps) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add allowed application: $packageName", e)
                    }
                }
            } else {
                try {
                    builder.addAllowedApplication("com.opera.browser")
                    builder.addAllowedApplication("com.android.chrome")
                } catch (e: Exception) {
                    Log.w(TAG, "Opera/Chrome package fallback")
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface.")
                return
            }

            val input  = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)
            // Исправление #3: пресет из
            val config = ConfigManager.loadPreset(activePresetId)

            while (isRunning) {
                val length = input.read(buffer.array())
                if (length > 0) {
                    // Исправление #1: копия до submit, чтобы последующий read() не перезаписал общий массив
                    val packetCopy = buffer.array().copyOf(length)
                    executorService?.submit {
                        try {
                            // Исправление #2: synchronized исключает перемешивание фрагментов из разных потоков
                            synchronized(output) {
                                PacketProcessor.processPacket(packetCopy, length, output, config)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in packet loop", e)
                        }
                    }
                    buffer.clear()
                }
                // Исправление #4: Thread.sleep(1) удалён — input.read() итак блокирующий
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN thread interrupted.")
        } catch (e: IOException) {
            Log.e(TAG, "VPN IO exception", e)
        } finally {
            stopVpn()
        }
    }

    // ── Foreground Service helpers ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN сервис",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "myVPNproject DPI bypass" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("myVPNproject активен")
            .setContentText("DPI bypass работает • пресет: $activePresetId")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }
}
