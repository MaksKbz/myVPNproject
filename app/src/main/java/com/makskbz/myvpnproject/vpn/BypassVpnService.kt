package com.makskbz.myvpnproject.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
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
    }

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var executorService: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)
            startVpn(allowedApps)
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(allowedApps: ArrayList<String>?) {
        if (isRunning) return
        isRunning = true
        executorService = Executors.newCachedThreadPool()
        vpnThread = Thread({ runVpn(allowedApps) }, "BypassVpnThread").apply { start() }
        Log.i(TAG, "VPN service 1.08 started.")
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
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
        Log.i(TAG, "VPN service 1.08 stopped.")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun run() {}

    private fun runVpn(allowedApps: ArrayList<String>?) {
        try {
            // КОНФИГУРАЦИЯ ИНТЕРФЕЙСА VPN (B4 / ByeDPI-style):
            // Чтобы трафик выбранного приложения (например, Opera, Chrome) корректно проходил через
            // локальный обработчик пакетов (Userspace Bypass), мы перехватываем весь трафик этого приложения.
            // addRoute("0.0.0.0", 0) направляет IPv4 трафик в TUN-интерфейс.
            
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 24) // Маска подсети 24 для правильной изоляции интерфейса
                .addRoute("0.0.0.0", 0)     // Перехватываем весь IPv4 трафик целевого приложения
                .addDnsServer("1.1.1.1")     // DoH резолвер Cloudflare
                .addDnsServer("8.8.8.8")     // DoH резолвер Google
                .setMtu(1500)

            // Применяем выборочное туннелирование (Split Tunneling)
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
                // Если пользователь ничего не выбрал, по умолчанию направляем Оперу и Chrome
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

            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            while (isRunning) {
                val length = input.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    buffer.rewind()

                    executorService?.submit {
                        try {
                            val processedLength = PacketProcessor.processPacket(buffer, length)
                            if (processedLength > 0) {
                                synchronized(output) {
                                    output.write(buffer.array(), 0, processedLength)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in packet loop", e)
                        }
                    }
                    buffer.clear()
                }
                Thread.sleep(1)
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN thread interrupted.")
        } catch (e: IOException) {
            Log.e(TAG, "VPN IO exception", e)
        } finally {
            stopVpn()
        }
    }
}
