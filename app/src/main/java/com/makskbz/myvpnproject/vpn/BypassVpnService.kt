package com.makskbz.myvpnproject.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BypassVpnService : VpnService(), Runnable {

    companion object {
        const val TAG = "BypassVpnService"
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val EXTRA_ALLOWED_APPS = "ALLOWED_APPS"
        // Корректный MTU для TUN: 1500 - 20 (IP) - 20 (TCP) = 1460,
        // но с запасом на туннельный overhead берём 1400
        private const val TUN_MTU = 1400
        // Размер буфера чтения = MTU + небольшой запас
        private const val READ_BUFFER_SIZE = TUN_MTU + 100
    }

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    // AtomicBoolean для корректного visibility между потоками
    private val isRunning = AtomicBoolean(false)
    private var executorService: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)
                startVpn(allowedApps)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(allowedApps: ArrayList<String>?) {
        if (isRunning.getAndSet(true)) return
        // Фиксированный пул: 2 потока достаточно для обработки пакетов без overhead
        executorService = Executors.newFixedThreadPool(2)
        vpnThread = Thread({ runVpn(allowedApps) }, "BypassVpnThread").apply { start() }
        Log.i(TAG, "VPN service 2.0 started (MTU=$TUN_MTU)")
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) return
        try { vpnInterface?.close() } catch (e: Exception) { Log.e(TAG, "Error closing VPN interface", e) }
        vpnInterface = null
        vpnThread?.interrupt()
        vpnThread = null
        executorService?.shutdownNow()
        executorService = null
        stopSelf()
        Log.i(TAG, "VPN service 2.0 stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun run() {}

    private fun runVpn(allowedApps: ArrayList<String>?) {
        try {
            val builder = Builder()
                .setSession("myVPNproject v2.0")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(TUN_MTU)  // исправлено: было 1500

            if (!allowedApps.isNullOrEmpty()) {
                Log.i(TAG, "Split tunneling for: $allowedApps")
                for (pkg in allowedApps) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (e: Exception) { Log.w(TAG, "Cannot add app: $pkg", e) }
                }
            } else {
                // Дефолтные приложения
                listOf("com.opera.browser", "com.android.chrome").forEach { pkg ->
                    try { builder.addAllowedApplication(pkg) }
                    catch (e: Exception) { Log.w(TAG, "Fallback: cannot add $pkg") }
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            val input  = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)

            // ИСПРАВЛЕНО: используем блокирующее чтение без Thread.sleep()
            // Каждый пакет копируется в собственный ByteArray — устраняет race condition
            while (isRunning.get()) {
                // Выделяем буфер на каждую итерацию чтобы избежать data race
                val readBuf = ByteArray(READ_BUFFER_SIZE)
                val length = input.read(readBuf)
                if (length <= 0) continue

                // Делаем независимую копию для передачи в поток
                val packetCopy = readBuf.copyOf(length)

                executorService?.submit {
                    try {
                        // output защищён synchronized — единственная точка записи в TUN
                        synchronized(output) {
                            PacketProcessor.processPacket(packetCopy, length, output)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Packet processing error", e)
                    }
                }
            }

        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN thread interrupted")
        } catch (e: IOException) {
            Log.e(TAG, "VPN IO exception", e)
        } finally {
            stopVpn()
        }
    }
}
