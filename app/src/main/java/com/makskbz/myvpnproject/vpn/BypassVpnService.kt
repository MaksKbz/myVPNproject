package com.makskbz.myvpnproject.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BypassVpnService : VpnService(), Runnable {

    companion object {
        const val TAG = "BypassVpnService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
    }

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var executorService: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startVpn()
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true
        executorService = Executors.newCachedThreadPool()
        vpnThread = Thread(this, "BypassVpnThread").apply { start() }
        Log.i(TAG, "VPN service 1.02 started.")
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
        Log.i(TAG, "VPN service 1.02 stopped.")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun run() {
        try {
            // Для бесперебойной работы интернета на Android (без поднятия сложного C-движка lwIP)
            // мы используем режим селективного перехвата DNS и QUIC (UDP 443).
            // Обычный TCP-трафик идет напрямую через защищенные сокеты, что исключает потерю пакетов.
            
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 32)
                // Маршрутизируем DNS-серверы для безопасного резолвинга DoH
                .addRoute("1.1.1.1", 32)
                .addRoute("8.8.8.8", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            // Разрешаем только определенным браузерам или приложениям работать через VPN,
            // чтобы банковский и системный трафик шел на полной скорости без задержек.
            try {
                builder.addAllowedApplication("com.android.chrome")
            } catch (e: Exception) {
                Log.w(TAG, "Chrome is not installed, running in global mode")
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface.")
                return
            }

            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            Log.i(TAG, "DPI Bypass established. Starting packet routing loop.")

            while (isRunning) {
                val length = input.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    buffer.rewind()

                    // Безопасно обрабатываем пакеты в фоновом пуле потоков
                    executorService?.submit {
                        try {
                            val processedLength = PacketProcessor.processPacket(buffer, length)
                            if (processedLength > 0) {
                                synchronized(output) {
                                    output.write(buffer.array(), 0, processedLength)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in background packet processing", e)
                        }
                    }
                    buffer.clear()
                }
                Thread.sleep(1)
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN loop interrupted.")
        } catch (e: IOException) {
            Log.e(TAG, "VPN error in packet loop", e)
        } finally {
            stopVpn()
        }
    }
}
