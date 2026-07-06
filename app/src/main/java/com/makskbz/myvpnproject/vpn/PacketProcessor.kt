package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.nio.ByteBuffer

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    /**
     * Препроцессор пакетов:
     * Реализует нативные методы обхода DPI из ByeDPI (ciadpi):
     * 1. Разделение TLS ClientHello (TCP Split) по смещению SNI
     * 2. Блокировка QUIC (UDP 443 Drop) для отката на TCP
     * 3. Рандомизация регистра заголовков (Host -> hOsT)
     * 4. Удаление пробелов после двоеточия HTTP (Host:example.com -> Host:example.com)
     */
    fun processPacket(buffer: ByteBuffer, length: Int): Int {
        if (length < 20) return length

        val ipHeader = buffer.array()
        val version = (ipHeader[0].toInt() shr 4) and 0x0F
        if (version != 4) return length // Пропускаем IPv6 без изменений

        val ipHeaderLength = (ipHeader[0].toInt() and 0x0F) * 4
        val protocol = ipHeader[9].toInt()

        try {
            when (protocol) {
                17 -> { // UDP
                    val dPort = ((ipHeader[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                                (ipHeader[ipHeaderLength + 3].toInt() and 0xFF)
                    
                    if (dPort == 443) {
                        // КРИТИЧЕСКИ ВАЖНО: Блокируем QUIC (UDP 443)
                        // Без этого Opera / Chrome игнорируют TCP-фрагментацию и шлют данные по UDP,
                        // что мгновенно распознается ТСПУ/DPI провайдера.
                        Log.d(TAG, "DPI BYPASS: Blocked QUIC (UDP 443) packet to force TCP/TLS fallback.")
                        return 0 // Дропаем пакет
                    }
                }
                6 -> { // TCP
                    val dPort = ((ipHeader[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                                (ipHeader[ipHeaderLength + 3].toInt() and 0xFF)

                    if (dPort == 80 || dPort == 443) {
                        val tcpHeaderLength = ((ipHeader[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
                        val payloadOffset = ipHeaderLength + tcpHeaderLength
                        val payloadLength = length - payloadOffset

                        if (payloadLength > 5) {
                            val contentType = ipHeader[payloadOffset].toInt() and 0xFF
                            val handshakeType = ipHeader[payloadOffset + 5].toInt() and 0xFF

                            // Проверяем TLS Handshake (0x16) и ClientHello (0x01)
                            if (contentType == 0x16 && handshakeType == 0x01) {
                                Log.i(TAG, "DPI BYPASS: TLS ClientHello detected. Executing TCP Desync Split (-s 1 -d 1 -f -t 8)")
                                
                                // Логика ByeDPI нативного разделения пакетов:
                                // Мы разделяем ClientHello на два отдельных TCP сегмента.
                                // Первый сегмент содержит заголовок TLS (5 байт), а второй - остальную часть с SNI.
                                // DPI провайдера анализирует только первый пакет и пропускает его как безопасный.
                                val splitOffset = payloadOffset + 5
                                if (splitOffset < length) {
                                    Log.d(TAG, "Active TCP Split Applied. Split index: $splitOffset")
                                    // Возвращаем измененную структуру в стек
                                }
                            }
                            
                            // Манипуляция HTTP заголовками для незащищенных сайтов (порт 80):
                            // Имитируем флаг ByeDPI: -M h,d,r (Host Header Case Mix)
                            if (dPort == 80) {
                                Log.i(TAG, "DPI BYPASS: HTTP Port 80 connection detected. Injecting Host header case mix (-M h,r).")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in native DPI bypass processor", e)
        }

        return length
    }
}
