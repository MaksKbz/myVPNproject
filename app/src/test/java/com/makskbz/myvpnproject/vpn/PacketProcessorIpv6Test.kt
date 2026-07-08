package com.makskbz.myvpnproject.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * v3.6 — тесты полного IPv6 TCP split (был passthrough в v3.5, из-за
 * синтаксической ошибки в Kotlin при первой попытке смержить откатили
 * до заглушки — см. roadmap п.2). Эти тесты закрывают регрессию и
 * фиксируют контракт: TLS ClientHello по IPv6 должен разбиваться на два
 * валидных TCP-сегмента с корректными checksum и seq-числами, так же как
 * для IPv4.
 */
class PacketProcessorIpv6Test {

    private val clientHelloMarker = byteArrayOf(
        0x16, 0x03, 0x01, // TLS record: handshake, TLSv1.0-in-record
        0x00, 0x20,       // record length = 32
        0x01              // handshake type = ClientHello
    )

    private fun buildIpv6TlsClientHello(payloadLen: Int = 40): ByteArray {
        val srcIp = InetAddress.getByName("fd00:1:fd00:1:fd00:1:fd00:1").address
        val dstIp = InetAddress.getByName("2606:4700:4700::1111").address
        val tcpHdrLen = 20
        val payload = ByteArray(payloadLen)
        System.arraycopy(clientHelloMarker, 0, payload, 0, clientHelloMarker.size)
        for (i in clientHelloMarker.size until payloadLen) payload[i] = (0x41 + (i % 20)).toByte()

        val totalLen = ChecksumUtils.IPV6_HEADER_LEN + tcpHdrLen + payload.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x60
        ChecksumUtils.setIpv6PayloadLength(pkt, tcpHdrLen + payload.size)
        pkt[6] = 6 // TCP
        pkt[7] = 64
        System.arraycopy(srcIp, 0, pkt, 8, 16)
        System.arraycopy(dstIp, 0, pkt, 24, 16)

        val tcpOff = ChecksumUtils.IPV6_HEADER_LEN
        pkt[tcpOff] = 0xC0.toByte(); pkt[tcpOff + 1] = 0x00 // src port
        pkt[tcpOff + 2] = 0x01; pkt[tcpOff + 3] = 0xBB.toByte() // dst port 443
        pkt[tcpOff + 4] = 0; pkt[tcpOff + 5] = 0; pkt[tcpOff + 6] = 0; pkt[tcpOff + 7] = 100 // seq = 100
        pkt[tcpOff + 12] = (5 shl 4).toByte()
        pkt[tcpOff + 13] = 0x18
        pkt[tcpOff + 14] = 0xFF.toByte(); pkt[tcpOff + 15] = 0xFF.toByte()

        System.arraycopy(payload, 0, pkt, tcpOff + tcpHdrLen, payload.size)
        ChecksumUtils.recalcTcpChecksumV6(pkt, ChecksumUtils.IPV6_HEADER_LEN)
        return pkt
    }

    private fun readIpv6PayloadLength(pkt: ByteArray): Int =
        ((pkt[4].toInt() and 0xFF) shl 8) or (pkt[5].toInt() and 0xFF)

    private fun readSeq(pkt: ByteArray, tcpOff: Int): Long {
        return ((pkt[tcpOff + 4].toLong() and 0xFF) shl 24) or
                ((pkt[tcpOff + 5].toLong() and 0xFF) shl 16) or
                ((pkt[tcpOff + 6].toLong() and 0xFF) shl 8) or
                (pkt[tcpOff + 7].toLong() and 0xFF)
    }

    private fun readTcpChecksum(pkt: ByteArray, tcpOff: Int): Int =
        ((pkt[tcpOff + 16].toInt() and 0xFF) shl 8) or (pkt[tcpOff + 17].toInt() and 0xFF)

    @Test
    fun `IPv6 TLS ClientHello is split into two segments with advanced seq and valid checksums`() {
        val pkt = buildIpv6TlsClientHello(payloadLen = 40)
        val config = BypassConfig(splitPosition = "3")
        val out = ByteArrayOutputStream()

        val handled = PacketProcessor.processPacket(pkt, pkt.size, out, config)
        assertTrue(handled)

        val written = out.toByteArray()
        // Должно быть записано БОЛЬШЕ байт, чем в исходном пакете (два IP/TCP-заголовка вместо одного)
        assertTrue("expected split into 2 segments, got same/less bytes", written.size > pkt.size)

        val tcpHdrLen = 20
        val ipHdrLen = ChecksumUtils.IPV6_HEADER_LEN

        // -- Фрагмент 1 --
        val frag1PayloadLen = 3
        val pkt1Len = ipHdrLen + tcpHdrLen + frag1PayloadLen
        val pkt1 = written.copyOfRange(0, pkt1Len)
        assertEquals(frag1PayloadLen + tcpHdrLen, readIpv6PayloadLength(pkt1))
        assertEquals(100L, readSeq(pkt1, ipHdrLen))

        // -- Фрагмент 2 --
        val pkt2 = written.copyOfRange(pkt1Len, written.size)
        val frag2PayloadLen = 40 - frag1PayloadLen
        assertEquals(ipHdrLen + tcpHdrLen + frag2PayloadLen, pkt2.size)
        assertEquals(frag2PayloadLen + tcpHdrLen, readIpv6PayloadLength(pkt2))
        // seq должен продвинуться ровно на длину первого фрагмента
        assertEquals(100L + frag1PayloadLen, readSeq(pkt2, ipHdrLen))

        // Обе чек-суммы пересчитаны (не равны нулю — гарантированно ненулевые для непустого TCP-сегмента
        // с этими данными) и валидны относительно IPv6 pseudo-header.
        val cs1 = readTcpChecksum(pkt1, ipHdrLen)
        val cs2 = readTcpChecksum(pkt2, ipHdrLen)

        val verify1 = pkt1.copyOf()
        val savedCs1High = verify1[ipHdrLen + 16]; val savedCs1Low = verify1[ipHdrLen + 17]
        ChecksumUtils.recalcTcpChecksumV6(verify1, ipHdrLen)
        assertEquals("checksum of frag1 must be internally consistent", savedCs1High, verify1[ipHdrLen + 16])
        assertEquals(savedCs1Low, verify1[ipHdrLen + 17])

        val verify2 = pkt2.copyOf()
        val savedCs2High = verify2[ipHdrLen + 16]; val savedCs2Low = verify2[ipHdrLen + 17]
        ChecksumUtils.recalcTcpChecksumV6(verify2, ipHdrLen)
        assertEquals("checksum of frag2 must be internally consistent", savedCs2High, verify2[ipHdrLen + 16])
        assertEquals(savedCs2Low, verify2[ipHdrLen + 17])

        assertTrue(cs1 != 0 && cs2 != 0)
    }

    @Test
    fun `non-TLS IPv6 TCP traffic passes through unchanged`() {
        val srcIp = InetAddress.getByName("fd00:1:fd00:1:fd00:1:fd00:1").address
        val dstIp = InetAddress.getByName("2606:4700:4700::1111").address
        val tcpHdrLen = 20
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val totalLen = ChecksumUtils.IPV6_HEADER_LEN + tcpHdrLen + payload.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x60
        ChecksumUtils.setIpv6PayloadLength(pkt, tcpHdrLen + payload.size)
        pkt[6] = 6
        pkt[7] = 64
        System.arraycopy(srcIp, 0, pkt, 8, 16)
        System.arraycopy(dstIp, 0, pkt, 24, 16)
        val tcpOff = ChecksumUtils.IPV6_HEADER_LEN
        pkt[tcpOff + 2] = 0x00; pkt[tcpOff + 3] = 80.toByte() // dst port 80, не 443
        pkt[tcpOff + 12] = (5 shl 4).toByte()
        System.arraycopy(payload, 0, pkt, tcpOff + tcpHdrLen, payload.size)

        val out = ByteArrayOutputStream()
        PacketProcessor.processPacket(pkt, pkt.size, out, BypassConfig())
        assertEquals(pkt.size, out.size())
    }

    @Test
    fun `IPv6 UDP port 443 (QUIC) is dropped`() {
        val srcIp = InetAddress.getByName("fd00::1").address
        val dstIp = InetAddress.getByName("2606:4700::1").address
        val udpLen = 8 + 12 // header + tiny payload
        val totalLen = ChecksumUtils.IPV6_HEADER_LEN + udpLen
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x60
        ChecksumUtils.setIpv6PayloadLength(pkt, udpLen)
        pkt[6] = 17 // UDP
        pkt[7] = 64
        System.arraycopy(srcIp, 0, pkt, 8, 16)
        System.arraycopy(dstIp, 0, pkt, 24, 16)
        val udpOff = ChecksumUtils.IPV6_HEADER_LEN
        pkt[udpOff + 2] = 0x01; pkt[udpOff + 3] = 0xBB.toByte() // dst port 443

        val out = ByteArrayOutputStream()
        val handled = PacketProcessor.processPacket(pkt, pkt.size, out, BypassConfig())
        assertTrue(handled)
        assertEquals(0, out.size()) // дропнут, ничего не записано
    }
}
