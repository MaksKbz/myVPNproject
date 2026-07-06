package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    /**
     * Обрабатывает сырые IP-пакеты и записывает результат в output.
     * Возвращает true, если пакет был обработан (или отброшен), false — если нужно записать оригинал.
     *
     * Ключевые исправления v2.0:
     * - Реализована настоящая фрагментация TLS ClientHello (два отдельных TCP-сегмента)
     * - Исправлен парсинг TLS: проверяется версия, длина записи и тип handshake
     * - QUIC-блокировка оставлена как есть (дроп UDP/443)
     */
    fun processPacket(packetBytes: ByteArray, length: Int, output: OutputStream): Boolean {
        if (length < 20) {
            output.write(packetBytes, 0, length)
            return true
        }

        val ipVersion = (packetBytes[0].toInt() shr 4) and 0x0F
        if (ipVersion != 4) {
            // IPv6 — пропускаем без изменений
            output.write(packetBytes, 0, length)
            return true
        }

        val ipHeaderLen = (packetBytes[0].toInt() and 0x0F) * 4
        val protocol = packetBytes[9].toInt() and 0xFF

        return try {
            when (protocol) {
                17 -> handleUdp(packetBytes, length, ipHeaderLen, output)
                6  -> handleTcp(packetBytes, length, ipHeaderLen, output)
                else -> {
                    output.write(packetBytes, 0, length)
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet", e)
            output.write(packetBytes, 0, length)
            true
        }
    }

    private fun handleUdp(pkt: ByteArray, length: Int, ipHdrLen: Int, output: OutputStream): Boolean {
        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                    (pkt[ipHdrLen + 3].toInt() and 0xFF)
        return if (dPort == 443) {
            // Блокируем QUIC/HTTP3 — дроп пакета, возвращаем true (обработано)
            Log.d(TAG, "QUIC/UDP 443 dropped — forcing TCP/TLS fallback")
            true
        } else {
            output.write(pkt, 0, length)
            true
        }
    }

    private fun handleTcp(pkt: ByteArray, length: Int, ipHdrLen: Int, output: OutputStream): Boolean {
        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                    (pkt[ipHdrLen + 3].toInt() and 0xFF)

        if (dPort != 443) {
            output.write(pkt, 0, length)
            return true
        }

        val tcpHdrLen = ((pkt[ipHdrLen + 12].toInt() shr 4) and 0x0F) * 4
        val payloadOffset = ipHdrLen + tcpHdrLen
        val payloadLen = length - payloadOffset

        // Минимальная длина TLS record header = 5 байт + 4 байта handshake header = 9 байт
        if (payloadLen < 9) {
            output.write(pkt, 0, length)
            return true
        }

        val contentType  = pkt[payloadOffset].toInt() and 0xFF       // 0x16 = TLS Handshake
        val tlsVerMajor  = pkt[payloadOffset + 1].toInt() and 0xFF   // 0x03
        val tlsVerMinor  = pkt[payloadOffset + 2].toInt() and 0xFF   // 0x01–0x04
        val recordLen    = ((pkt[payloadOffset + 3].toInt() and 0xFF) shl 8) or
                           (pkt[payloadOffset + 4].toInt() and 0xFF)
        val handshakeType = pkt[payloadOffset + 5].toInt() and 0xFF  // 0x01 = ClientHello

        val isTlsHandshake = contentType == 0x16 &&
                             tlsVerMajor == 0x03 &&
                             tlsVerMinor in 0x01..0x04 &&
                             handshakeType == 0x01 &&
                             recordLen > 0 &&
                             recordLen <= payloadLen - 5

        if (!isTlsHandshake) {
            output.write(pkt, 0, length)
            return true
        }

        Log.i(TAG, "TLS ClientHello detected — applying TCP segment split")

        // === РЕАЛЬНАЯ ФРАГМЕНТАЦИЯ ===
        // Разбиваем payload на два сегмента:
        //   fragment1: первые 3 байта TLS payload (до тела ClientHello)
        //   fragment2: остаток
        // Для каждого фрагмента строим корректный IP+TCP пакет с пересчитанными длинами.

        val frag1PayloadLen = 3
        val frag2PayloadLen = payloadLen - frag1PayloadLen

        if (frag2PayloadLen <= 0) {
            output.write(pkt, 0, length)
            return true
        }

        // -- Фрагмент 1 --
        val pkt1Len = payloadOffset + frag1PayloadLen
        val pkt1 = pkt.copyOf(pkt1Len)
        setIpTotalLength(pkt1, pkt1Len)
        recalcIpChecksum(pkt1, ipHdrLen)
        recalcTcpChecksum(pkt1, ipHdrLen, tcpHdrLen)
        output.write(pkt1, 0, pkt1Len)

        // -- Фрагмент 2: нужно сдвинуть TCP sequence number на frag1PayloadLen --
        val pkt2Len = payloadOffset + frag2PayloadLen
        val pkt2 = ByteArray(pkt2Len)
        // Копируем заголовки
        System.arraycopy(pkt, 0, pkt2, 0, payloadOffset)
        // Копируем оставшийся payload
        System.arraycopy(pkt, payloadOffset + frag1PayloadLen, pkt2, payloadOffset, frag2PayloadLen)
        // Сдвигаем SEQ
        advanceTcpSeq(pkt2, ipHdrLen, frag1PayloadLen)
        setIpTotalLength(pkt2, pkt2Len)
        recalcIpChecksum(pkt2, ipHdrLen)
        recalcTcpChecksum(pkt2, ipHdrLen, tcpHdrLen)
        output.write(pkt2, 0, pkt2Len)

        Log.d(TAG, "Split: frag1=$frag1PayloadLen bytes, frag2=$frag2PayloadLen bytes")
        return true
    }

    // ───────────────────────────────────────────────
    // Утилиты: работа с IP/TCP заголовками
    // ───────────────────────────────────────────────

    private fun setIpTotalLength(pkt: ByteArray, totalLen: Int) {
        pkt[2] = ((totalLen shr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
    }

    private fun advanceTcpSeq(pkt: ByteArray, ipHdrLen: Int, delta: Int) {
        val seqOffset = ipHdrLen + 4
        var seq = ((pkt[seqOffset].toInt() and 0xFF) shl 24) or
                  ((pkt[seqOffset + 1].toInt() and 0xFF) shl 16) or
                  ((pkt[seqOffset + 2].toInt() and 0xFF) shl 8) or
                  (pkt[seqOffset + 3].toInt() and 0xFF)
        seq = (seq + delta) and 0xFFFFFFFFL.toInt()
        pkt[seqOffset]     = ((seq shr 24) and 0xFF).toByte()
        pkt[seqOffset + 1] = ((seq shr 16) and 0xFF).toByte()
        pkt[seqOffset + 2] = ((seq shr 8)  and 0xFF).toByte()
        pkt[seqOffset + 3] = (seq and 0xFF).toByte()
    }

    private fun recalcIpChecksum(pkt: ByteArray, ipHdrLen: Int) {
        pkt[10] = 0; pkt[11] = 0
        val cs = internetChecksum(pkt, 0, ipHdrLen)
        pkt[10] = ((cs shr 8) and 0xFF).toByte()
        pkt[11] = (cs and 0xFF).toByte()
    }

    private fun recalcTcpChecksum(pkt: ByteArray, ipHdrLen: Int, tcpHdrLen: Int) {
        val tcpOffset = ipHdrLen
        val tcpLen = pkt.size - ipHdrLen
        // Обнуляем поле чексуммы TCP
        pkt[tcpOffset + 16] = 0; pkt[tcpOffset + 17] = 0
        // Строим псевдозаголовок IPv4
        val pseudo = ByteArray(12 + tcpLen)
        System.arraycopy(pkt, 12, pseudo, 0, 4)  // src IP
        System.arraycopy(pkt, 16, pseudo, 4, 4)  // dst IP
        pseudo[8]  = 0
        pseudo[9]  = 6  // protocol = TCP
        pseudo[10] = ((tcpLen shr 8) and 0xFF).toByte()
        pseudo[11] = (tcpLen and 0xFF).toByte()
        System.arraycopy(pkt, ipHdrLen, pseudo, 12, tcpLen)
        val cs = internetChecksum(pseudo, 0, pseudo.size)
        pkt[tcpOffset + 16] = ((cs shr 8) and 0xFF).toByte()
        pkt[tcpOffset + 17] = (cs and 0xFF).toByte()
    }

    private fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((offset + length) % 2 != 0) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
