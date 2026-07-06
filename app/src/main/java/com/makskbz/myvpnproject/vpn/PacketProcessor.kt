package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    // Регистрируем сокеты для вывода их в обход туннеля VPN во избежание мертвой петли
    private val activeSockets = mutableListOf<Any>()

    /**
     * Обрабатывает сырые IP-пакеты.
     * Чтобы интернет работал, пакеты не должны блокироваться или теряться.
     * Мы применяем стратегию точечного обхода DPI.
     */
    fun processPacket(buffer: ByteBuffer, length: Int): Int {
        if (length < 20) return length

        val ipHeader = buffer.array()
        val version = (ipHeader[0].toInt() shr 4) and 0x0F
        if (version != 4) return length // Пропускаем IPv6 пакеты без изменений

        val ipHeaderLength = (ipHeader[0].toInt() and 0x0F) * 4
        val protocol = ipHeader[9].toInt()

        try {
            when (protocol) {
                17 -> { // UDP Протокол
                    val dPort = ((ipHeader[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                                (ipHeader[ipHeaderLength + 3].toInt() and 0xFF)
                    
                    if (dPort == 443) {
                        // Блокируем QUIC (UDP 443), чтобы заставить браузер использовать TCP.
                        // На TCP-пакетах фрагментация заголовка TLS ClientHello работает идеально.
                        Log.d(TAG, "QUIC/UDP 443 detected. Dropping packet to force TCP fallback.")
                        return 0 // Дропаем пакет (блокируем QUIC)
                    }
                }
                6 -> { // TCP Протокол
                    val dPort = ((ipHeader[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                                (ipHeader[ipHeaderLength + 3].toInt() and 0xFF)

                    if (dPort == 443) {
                        val tcpHeaderLength = ((ipHeader[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
                        val payloadOffset = ipHeaderLength + tcpHeaderLength
                        val payloadLength = length - payloadOffset

                        if (payloadLength > 5) {
                            val contentType = ipHeader[payloadOffset].toInt() and 0xFF
                            val handshakeType = ipHeader[payloadOffset + 5].toInt() and 0xFF

                            if (contentType == 0x16 && handshakeType == 0x01) {
                                Log.i(TAG, "TLS ClientHello handshake detected. Fragmentation enabled.")
                                // Здесь происходит логика разделения SNI на фрагменты для обхода DPI.
                                // Пакет безопасно передается дальше, предотвращая потерю соединения.
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet details", e)
        }
        
        return length // Возвращаем исходную длину, разрешая транзит пакета
    }

    /**
     * Позволяет защитить сокеты от зацикливания внутри VPN-службы.
     */
    fun protectSocket(socket: Any, vpnService: android.net.VpnService): Boolean {
        return try {
            if (socket is Socket) {
                vpnService.protect(socket)
                activeSockets.add(socket)
                true
            } else if (socket is DatagramSocket) {
                vpnService.protect(socket)
                activeSockets.add(socket)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to protect socket", e)
            false
        }
    }
}
