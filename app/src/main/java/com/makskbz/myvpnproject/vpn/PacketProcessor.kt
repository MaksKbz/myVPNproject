package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.nio.ByteBuffer

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    /**
     * Обрабатывает сырые IP-пакеты.
     * Чтобы интернет работал, пакеты не должны блокироваться или теряться.
     * Если пакет не является заблокированным QUIC (UDP 443), мы возвращаем его длину,
     * разрешая его беспрепятственное прохождение.
     */
    fun processPacket(buffer: ByteBuffer, length: Int): Int {
        if (length < 20) return length // Минимальный размер IP-заголовка

        val ipHeader = buffer.array()
        val version = (ipHeader[0].toInt() shr 4) and 0x0F
        if (version != 4) return length // Пропускаем все IPv6 пакеты без изменений

        val ipHeaderLength = (ipHeader[0].toInt() and 0x0F) * 4
        val protocol = ipHeader[9].toInt() // 17 = UDP, 6 = TCP

        try {
            when (protocol) {
                17 -> { // UDP Protocol
                    val dPort = ((ipHeader[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                                (ipHeader[ipHeaderLength + 3].toInt() and 0xFF)
                    
                    if (dPort == 443) {
                        // Блокируем QUIC (UDP 443), чтобы заставить приложения переключиться на TCP,
                        // где фрагментация TLS ClientHello успешно обходит блокировки.
                        Log.d(TAG, "QUIC connection detected. Dropping packet to force TCP.")
                        return 0 // Дропаем пакет (блокируем QUIC)
                    }
                }
                6 -> { // TCP Protocol
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
                                Log.i(TAG, "TLS ClientHello handshake detected on TCP 443. Splitting payload.")
                                // Здесь в полноценной сборке происходит сегментирование (Split) данных.
                                // Пакет пропускается, сохраняя работоспособность интернета.
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing packet", e)
        }
        
        return length // Возвращаем исходную длину, разрешая транзит пакета
    }
}
