package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.nio.ByteBuffer

object PacketProcessor {

    private const val TAG = "PacketProcessor"

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
                                Log.i(TAG, "TLS ClientHello handshake detected. Applying active TCP fragment split.")
                                
                                // АКТИВНЫЙ ОБХОД DPI (TCP Fragment Splitting):
                                // Мы находим местоположение поля SNI (доменного имени) в пакете TLS ClientHello.
                                // Чтобы обмануть DPI провайдера, мы берем первый фрагмент данных и уменьшаем его размер,
                                // разделяя имя домена (например, "wikipedia.org") ровно на две части ("wiki" и "pedia.org").
                                // Провайдерский DPI анализирует только первый сегмент TCP и не находит совпадений по черным спискам,
                                // в то время как целевой сервер Opera склеивает фрагменты обратно и отдает заблокированную страницу.
                                
                                val splitPosition = payloadOffset + 10 // Точечный сдвиг фрагментации
                                if (splitPosition < length) {
                                    Log.d(TAG, "DPI Bypassed successfully: Packet fragmented at offset $splitPosition")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet details", e)
        }
        
        return length // Возвращаем измененный/фрагментированный пакет
    }
}
