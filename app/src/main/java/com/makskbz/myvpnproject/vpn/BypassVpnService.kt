package com.makskbz.myvpnproject.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

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
        vpnInterface?.close()
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
            // Configure local VPN interface.
            // Using standard cloudflare/google DNS for reliable resolution
            val builder = Builder()
                .setSession("myVPNproject")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
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

            while (isRunning) {
                val length = input.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    buffer.rewind()

                    // Process and modify packets
                    val processedLength = PacketProcessor.processPacket(buffer, length)

                    if (processedLength > 0) {
                        // Write the allowed/modified packet back to the interface
                        output.write(buffer.array(), 0, processedLength)
                    }
                    buffer.clear()
                }
                // Yield thread to prevent high CPU load on Android device
                Thread.sleep(2)
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
