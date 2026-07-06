package com.makskbz.myvpnproject.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel

class BypassVpnService : VpnService(), Runnable {

    companion object {
        const val TAG = "BypassVpnService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
    }

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

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
        vpnThread = Thread(this, "BypassVpnThread").apply { start() }
        Log.i(TAG, "VPN service started.")
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
        stopSelf()
        Log.i(TAG, "VPN service stopped.")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun run() {
        try {
            // Инициализируем VPN-интерфейс Android.
            // Чтобы дать реальный доступ в интернет, VpnService должен использовать правильные маршруты.
            // Для rootless обхода DPI без внешних серверов, VpnService должен настроить защищенный туннель,
            // но поскольку мы не отправляем пакеты на удаленный прокси, мы настраиваем локальный прокси-сервер 
            // или точечно защищаем системные сокеты через метод protect().
            
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 32)
                .addRoute("1.1.1.1", 32) // Перехватываем только DNS-запросы
                .addRoute("8.8.8.8", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface.")
                return
            }

            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            Log.i(TAG, "Rootless VpnService successfully established. Routing traffic.")

            while (isRunning) {
                val length = input.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    buffer.rewind()

                    // Анализируем и модифицируем пакеты локально
                    val processedLength = PacketProcessor.processPacket(buffer, length)
                    if (processedLength > 0) {
                        output.write(buffer.array(), 0, processedLength)
                    }
                    buffer.clear()
                }
                Thread.sleep(5)
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
