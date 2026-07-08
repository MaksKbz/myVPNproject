package com.makskbz.myvpnproject.vpn

/**
 * ChecksumUtils — v3.6 CIS-MAX
 *
 * Общая логика пересчёта IP/TCP/UDP контрольных сумм для IPv4 и IPv6.
 * Вынесена в отдельный объект, чтобы PacketProcessor (TCP split) и
 * DnsInterceptor (синтез DNS-ответов) не дублировали одну и ту же
 * низкоуровневую арифметику — раньше IPv6 TCP checksum считался через
 * IPv4-псевдозаголовок "на скорую руку", что было некорректно
 * (см. TODO v3.6 в старой версии PacketProcessor.kt).
 */
object ChecksumUtils {

    /**
     * Классическая интернет-контрольная сумма (RFC 1071) по 16-битным словам.
     */
    fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((length and 1) != 0) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun writeChecksum(pkt: ByteArray, at: Int, cs: Int) {
        pkt[at]     = ((cs shr 8) and 0xFF).toByte()
        pkt[at + 1] = (cs and 0xFF).toByte()
    }

    // ───────────────────────────── IPv4 ─────────────────────────────

    fun recalcIpv4HeaderChecksum(pkt: ByteArray, ipHdrLen: Int) {
        pkt[10] = 0; pkt[11] = 0
        val cs = internetChecksum(pkt, 0, ipHdrLen)
        writeChecksum(pkt, 10, cs)
    }

    /** pkt должен быть обрезан ровно по концу TCP-сегмента (pkt.size == ipHdrLen + tcpLen). */
    fun recalcTcpChecksumV4(pkt: ByteArray, ipHdrLen: Int) {
        val tcpLen = pkt.size - ipHdrLen
        pkt[ipHdrLen + 16] = 0; pkt[ipHdrLen + 17] = 0
        val pseudo = ByteArray(12 + tcpLen)
        System.arraycopy(pkt, 12, pseudo, 0, 4)   // src IP
        System.arraycopy(pkt, 16, pseudo, 4, 4)   // dst IP
        pseudo[8]  = 0
        pseudo[9]  = 6 // TCP
        pseudo[10] = ((tcpLen shr 8) and 0xFF).toByte()
        pseudo[11] = (tcpLen and 0xFF).toByte()
        System.arraycopy(pkt, ipHdrLen, pseudo, 12, tcpLen)
        val cs = internetChecksum(pseudo, 0, pseudo.size)
        writeChecksum(pkt, ipHdrLen + 16, cs)
    }

    /** pkt должен быть обрезан ровно по концу UDP-датаграммы. */
    fun recalcUdpChecksumV4(pkt: ByteArray, ipHdrLen: Int) {
        val udpLen = pkt.size - ipHdrLen
        pkt[ipHdrLen + 6] = 0; pkt[ipHdrLen + 7] = 0
        val pseudo = ByteArray(12 + udpLen)
        System.arraycopy(pkt, 12, pseudo, 0, 4)
        System.arraycopy(pkt, 16, pseudo, 4, 4)
        pseudo[8]  = 0
        pseudo[9]  = 17 // UDP
        pseudo[10] = ((udpLen shr 8) and 0xFF).toByte()
        pseudo[11] = (udpLen and 0xFF).toByte()
        System.arraycopy(pkt, ipHdrLen, pseudo, 12, udpLen)
        val cs = internetChecksum(pseudo, 0, pseudo.size)
        val finalCs = if (cs == 0) 0xFFFF else cs // UDPv4: 0 значит "нет чексуммы" — избегаем
        writeChecksum(pkt, ipHdrLen + 6, finalCs)
    }

    fun setIpv4TotalLength(pkt: ByteArray, totalLen: Int) {
        pkt[2] = ((totalLen shr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
    }

    // ───────────────────────────── IPv6 ─────────────────────────────
    // IPv6-заголовок фиксированной длины 40 байт (расширения заголовков
    // здесь не поддерживаются — соответствует остальной части
    // PacketProcessor, который тоже предполагает next-header сразу после
    // основного 40-байтного заголовка).

    const val IPV6_HEADER_LEN = 40

    fun setIpv6PayloadLength(pkt: ByteArray, payloadLen: Int) {
        pkt[4] = ((payloadLen shr 8) and 0xFF).toByte()
        pkt[5] = (payloadLen and 0xFF).toByte()
    }

    /** pkt должен быть обрезан ровно по концу TCP-сегмента (pkt.size == 40 + tcpLen). */
    fun recalcTcpChecksumV6(pkt: ByteArray, ipHdrLen: Int = IPV6_HEADER_LEN) {
        val tcpLen = pkt.size - ipHdrLen
        pkt[ipHdrLen + 16] = 0; pkt[ipHdrLen + 17] = 0
        val pseudo = ByteArray(40 + tcpLen)
        System.arraycopy(pkt, 8, pseudo, 0, 16)   // src IPv6
        System.arraycopy(pkt, 24, pseudo, 16, 16) // dst IPv6
        pseudo[32] = ((tcpLen shr 24) and 0xFF).toByte()
        pseudo[33] = ((tcpLen shr 16) and 0xFF).toByte()
        pseudo[34] = ((tcpLen shr 8) and 0xFF).toByte()
        pseudo[35] = (tcpLen and 0xFF).toByte()
        pseudo[36] = 0; pseudo[37] = 0; pseudo[38] = 0
        pseudo[39] = 6 // next header = TCP
        System.arraycopy(pkt, ipHdrLen, pseudo, 40, tcpLen)
        val cs = internetChecksum(pseudo, 0, pseudo.size)
        writeChecksum(pkt, ipHdrLen + 16, cs)
    }

    /** pkt должен быть обрезан ровно по концу UDP-датаграммы (pkt.size == 40 + udpLen). */
    fun recalcUdpChecksumV6(pkt: ByteArray, ipHdrLen: Int = IPV6_HEADER_LEN) {
        val udpLen = pkt.size - ipHdrLen
        pkt[ipHdrLen + 6] = 0; pkt[ipHdrLen + 7] = 0
        val pseudo = ByteArray(40 + udpLen)
        System.arraycopy(pkt, 8, pseudo, 0, 16)
        System.arraycopy(pkt, 24, pseudo, 16, 16)
        pseudo[32] = ((udpLen shr 24) and 0xFF).toByte()
        pseudo[33] = ((udpLen shr 16) and 0xFF).toByte()
        pseudo[34] = ((udpLen shr 8) and 0xFF).toByte()
        pseudo[35] = (udpLen and 0xFF).toByte()
        pseudo[36] = 0; pseudo[37] = 0; pseudo[38] = 0
        pseudo[39] = 17 // next header = UDP
        System.arraycopy(pkt, ipHdrLen, pseudo, 40, udpLen)
        val cs = internetChecksum(pseudo, 0, pseudo.size)
        val finalCs = if (cs == 0) 0xFFFF else cs
        writeChecksum(pkt, ipHdrLen + 6, finalCs)
    }

    // ───────────────────────────── TCP seq ─────────────────────────────

    fun advanceTcpSeq(pkt: ByteArray, ipHdrLen: Int, delta: Int) {
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
}
