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
    @Volatile private var isRunning = false
    @Volatile private var activePresetId: String = "universal"
    @Volatile private var currentConfig: BypassConfig = ConfigManager.loadPreset("universal")

    // Статистика для авто-переключения
    @Volatile private var lastPacketTime = System.currentTimeMillis()
    @Volatile private var consecutiveFails = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Инициализируем DoH резолвер — защита от DNS-спуфинга РКН
        try {
            DohResolver.init(cacheDir)
            Log.i(TAG, "DoH resolver initialized")
        } catch (e: Exception) {
            Log.w(TAG, "DoH init failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        executorService = Executors.newCachedThreadPool()
        vpnThread = Thread({ runVpn(allowedApps) }, "BypassVpnThread").apply { start() }
        Log.i(TAG, "VPN service v3.5-hybrid started. Preset: $activePresetId")
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        monitorThread?.interrupt()
        monitorThread = null
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

    override fun run() {}

    /**
     * Hybrid VPN engine v3.5 — CIS/RU optimized
     * TUN → Kotlin PacketProcessor → Internet
     * + native ciadpi SOCKS5 127.0.0.1:1080
     */
    private fun runVpn(allowedApps: ArrayList<String>?) {
        try {
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
                .setMtu(1400)
                .setBlocking(true)

            if (!allowedApps.isNullOrEmpty()) {
                Log.i(TAG, "Split tunneling for: $allowedApps")
                for (pkg in allowedApps) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (e: Exception) { Log.w(TAG, "Skipping package: $pkg", e) }
                }
            } else {
                // System apps bypass — чтобы не ломать push
                val bypass = listOf(
                    "com.google.android.gms",
                    "com.google.android.gsf",
                    "com.android.vending"
                )
                for (bp in bypass) { try { builder.addDisallowedApplication(bp) } catch (_: Exception) {} }
            }

            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                isRunning = false
                return
            }

            currentConfig = ConfigManager.loadPreset(activePresetId)
            val tunFd = vpnInterface!!.fd

            val nativeOk = try {
                ProxyEngine.start(tunFd, currentConfig)
            } catch (e: Exception) {
                Log.e(TAG, "ProxyEngine start failed", e)
                false
            }
            Log.i(TAG, "Native engine started=$nativeOk preset=$activePresetId fd=$tunFd port=${currentConfig.socksPort}")

            startPresetMonitor()

            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            Log.i(TAG, "Starting Kotlin PacketProcessor loop — CIS optimized")
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
        // Сначала пробуем через DoH-клиент (защита от DNS-спуфинга)
        try {
            val client = try {
                DohResolver.getOkHttpClientWithDoh(cacheDir)
            } catch (_: Exception) { null }
            if (client != null) {
                for (u in urls) {
                    try {
                        val req = okhttp3.Request.Builder()
                            .url(u)
                            .header("User-Agent", "myVPNproject/3.6 CIS DoH")
                            .get()
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val code = resp.code
                            if (code in 200..399 || code == 204) {
                                Log.d(TAG, "Connectivity OK (DoH) $u -> $code")
                                return true
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH test failed, fallback to HttpURLConnection: ${e.message}")
        }
        // Fallback: старый метод
        for (u in urls) {
            try {
                val url = URL(u)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "myVPNproject/3.5 CIS")
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
            // Порядок для СНГ: universal → youtube → telegram → aggressive → minimal → universal
            val presets = listOf("universal", "youtube", "telegram", "aggressive", "minimal")
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
                    ProxyEngine.start(tunFd, currentConfig)
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
        stopVpn()
        super.onDestroy()
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
