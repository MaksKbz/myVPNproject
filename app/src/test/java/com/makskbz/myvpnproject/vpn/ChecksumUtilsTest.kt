package com.makskbz.myvpnproject.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * v3.6 — юнит-тесты для ChecksumUtils.
 *
 * Основная цель: доказать, что IPv6 TCP/UDP pseudo-header checksum
 * реализован ПРАВИЛЬНО (а не переиспользует IPv4 pseudo-header, как было
 * помечено TODO в PacketProcessor.kt v3.5). Проверяем через
 * "круговой" тест: собираем валидный TCP/IPv6-сегмент, пересчитываем
 * чек-сумму нашей функцией и сверяем со значением, которое пересчитал бы
 * стандартный алгоритм на независимо построенном pseudo-header.
 */
class ChecksumUtilsTest {

    private fun buildIpv6TcpPacket(payload: ByteArray): ByteArray {
        val srcIp = InetAddress.getByName("fd00:1:fd00:1:fd00:1:fd00:1").address
        val dstIp = InetAddress.getByName("2606:4700:4700::1111").address
        val tcpHdrLen = 20
        val totalLen = ChecksumUtils.IPV6_HEADER_LEN + tcpHdrLen + payload.size
        val pkt = ByteArray(totalLen)

        // IPv6 header
        pkt[0] = 0x60 // version 6
        val payloadLen = tcpHdrLen + payload.size
        ChecksumUtils.setIpv6PayloadLength(pkt, payloadLen)
        pkt[6] = 6 // next header = TCP
        pkt[7] = 64 // hop limit
        System.arraycopy(srcIp, 0, pkt, 8, 16)
        System.arraycopy(dstIp, 0, pkt, 24, 16)

        // TCP header (minimal, no options)
        val tcpOff = ChecksumUtils.IPV6_HEADER_LEN
        pkt[tcpOff]     = 0x00; pkt[tcpOff + 1] = 0x50 // src port 80
        pkt[tcpOff + 2] = 0x01; pkt[tcpOff + 3] = 0xBB.toByte() // dst port 443
        // seq num
        pkt[tcpOff + 4] = 0; pkt[tcpOff + 5] = 0; pkt[tcpOff + 6] = 0; pkt[tcpOff + 7] = 1
        // ack num
        pkt[tcpOff + 8] = 0; pkt[tcpOff + 9] = 0; pkt[tcpOff + 10] = 0; pkt[tcpOff + 11] = 0
        pkt[tcpOff + 12] = (5 shl 4).toByte() // data offset = 5 words (20 bytes), no flags high nibble
        pkt[tcpOff + 13] = 0x18 // PSH+ACK
        pkt[tcpOff + 14] = 0xFF.toByte(); pkt[tcpOff + 15] = 0xFF.toByte() // window
        // checksum placeholder (bytes 16-17) left as 0, urgent ptr 18-19 = 0

        System.arraycopy(payload, 0, pkt, tcpOff + tcpHdrLen, payload.size)
        return pkt
    }

    /** Независимая от ChecksumUtils реализация pseudo-header checksum — эталон для сверки. */
    private fun referenceTcpV6Checksum(pkt: ByteArray, ipHdrLen: Int): Int {
        val tcpLen = pkt.size - ipHdrLen
        val pseudo = ByteArray(40 + tcpLen)
        System.arraycopy(pkt, 8, pseudo, 0, 16)
        System.arraycopy(pkt, 24, pseudo, 16, 16)
        pseudo[35] = (tcpLen and 0xFF).toByte()
        pseudo[34] = ((tcpLen shr 8) and 0xFF).toByte()
        pseudo[39] = 6
        System.arraycopy(pkt, ipHdrLen, pseudo, 40, tcpLen)
        // Обнуляем поле чек-суммы внутри скопированного TCP-сегмента перед подсчётом
        pseudo[40 + 16] = 0; pseudo[40 + 17] = 0
        var sum = 0L
        var i = 0
        while (i < pseudo.size - 1) {
            sum += ((pseudo[i].toInt() and 0xFF) shl 8) or (pseudo[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (pseudo.size % 2 != 0) sum += (pseudo[pseudo.size - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    @Test
    fun `recalcTcpChecksumV6 matches independently computed pseudo-header checksum`() {
        val payload = "hello-ipv6-tls-split".toByteArray()
        val pkt = buildIpv6TcpPacket(payload)

        val expected = referenceTcpV6Checksum(pkt, ChecksumUtils.IPV6_HEADER_LEN)
        ChecksumUtils.recalcTcpChecksumV6(pkt, ChecksumUtils.IPV6_HEADER_LEN)
        val actualHigh = pkt[ChecksumUtils.IPV6_HEADER_LEN + 16].toInt() and 0xFF
        val actualLow = pkt[ChecksumUtils.IPV6_HEADER_LEN + 17].toInt() and 0xFF
        val actual = (actualHigh shl 8) or actualLow

        assertEquals(expected, actual)
    }

    @Test
    fun `recalcTcpChecksumV6 differs from a naive IPv4 pseudo-header reuse`() {
        // Регрессия против бага v3.5: IPv6-пакет НЕ должен получать ту же
        // чек-сумму, что дал бы IPv4-алгоритм (12-байтный псевдозаголовок
        // вместо 40-байтного) — иначе настоящие устройства отбросят пакет.
        val payload = "regression-check".toByteArray()
        val pkt = buildIpv6TcpPacket(payload)
        val pktCopy = pkt.copyOf()

        ChecksumUtils.recalcTcpChecksumV6(pkt, ChecksumUtils.IPV6_HEADER_LEN)

        // Наивный (неверный) вызов IPv4-функции на том же буфере привёл бы
        // к чтению байтов src/dst IP не с тех смещений (12/16 вместо 8/24)
        // и заведомо другой длине псевдозаголовка — считаем его и сверяем,
        // что он НЕ совпадает с правильным результатом.
        ChecksumUtils.recalcTcpChecksumV4(pktCopy, ChecksumUtils.IPV6_HEADER_LEN)

        val correct = (pkt[ChecksumUtils.IPV6_HEADER_LEN + 16].toInt() and 0xFF shl 8) or
                (pkt[ChecksumUtils.IPV6_HEADER_LEN + 17].toInt() and 0xFF)
        val naive = (pktCopy[ChecksumUtils.IPV6_HEADER_LEN + 16].toInt() and 0xFF shl 8) or
                (pktCopy[ChecksumUtils.IPV6_HEADER_LEN + 17].toInt() and 0xFF)

        assertTrue("IPv6 checksum must not equal naive IPv4-pseudo-header result", correct != naive)
    }

    @Test
    fun `internetChecksum of all-zero header is 0xFFFF`() {
        val zeros = ByteArray(20)
        val cs = ChecksumUtils.internetChecksum(zeros, 0, zeros.size)
        assertEquals(0xFFFF, cs)
    }

    @Test
    fun `setIpv6PayloadLength writes big-endian 16-bit length`() {
        val pkt = ByteArray(ChecksumUtils.IPV6_HEADER_LEN)
        ChecksumUtils.setIpv6PayloadLength(pkt, 0x1234)
        assertEquals(0x12, pkt[4].toInt() and 0xFF)
        assertEquals(0x34, pkt[5].toInt() and 0xFF)
    }

    @Test
    fun `advanceTcpSeq wraps around at 32-bit boundary`() {
        val pkt = ByteArray(ChecksumUtils.IPV6_HEADER_LEN + 20)
        val ipHdrLen = ChecksumUtils.IPV6_HEADER_LEN
        // seq = 0xFFFFFFFE
        pkt[ipHdrLen + 4] = 0xFF.toByte()
        pkt[ipHdrLen + 5] = 0xFF.toByte()
        pkt[ipHdrLen + 6] = 0xFF.toByte()
        pkt[ipHdrLen + 7] = 0xFE.toByte()

        ChecksumUtils.advanceTcpSeq(pkt, ipHdrLen, 4) // должно перейти через переполнение к 2

        val seq = ((pkt[ipHdrLen + 4].toInt() and 0xFF) shl 24) or
                ((pkt[ipHdrLen + 5].toInt() and 0xFF) shl 16) or
                ((pkt[ipHdrLen + 6].toInt() and 0xFF) shl 8) or
                (pkt[ipHdrLen + 7].toInt() and 0xFF)
        assertEquals(2, seq)
    }
}
