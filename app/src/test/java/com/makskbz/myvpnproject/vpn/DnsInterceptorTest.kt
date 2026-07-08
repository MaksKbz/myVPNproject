package com.makskbz.myvpnproject.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * v3.6 — тесты DnsInterceptor (roadmap п.3: "DoH-клиент, не только DNS-серверы").
 *
 * Проверяем, что "сырой" UDP:53-запрос, пришедший из TUN, никогда не
 * уходит наружу как есть: он либо перехватывается и превращается в
 * синтезированный DNS-ответ (полученный через DohResolver → в тестах
 * работает как системный резолвер для "localhost", т.к. это не требует
 * сети), либо, если распарсить/зарезолвить не удалось, молча дропается
 * (что тоже валидное поведение — предпочитаем "не резолвить" явной
 * утечке в открытом DNS).
 */
class DnsInterceptorTest {

    private fun encodeDnsQuery(id: Int, host: String, qtype: Int = 1): ByteArray {
        val header = ByteArray(12)
        header[0] = ((id shr 8) and 0xFF).toByte(); header[1] = (id and 0xFF).toByte()
        header[2] = 0x01 // RD=1
        header[5] = 0x01 // QDCOUNT = 1

        val labels = host.split(".")
        val qBytes = ByteArrayOutputStream()
        for (label in labels) {
            qBytes.write(label.length)
            qBytes.write(label.toByteArray(Charsets.US_ASCII))
        }
        qBytes.write(0) // root label
        qBytes.write((qtype shr 8) and 0xFF); qBytes.write(qtype and 0xFF) // QTYPE
        qBytes.write(0x00); qBytes.write(0x01) // QCLASS = IN

        return header + qBytes.toByteArray()
    }

    private fun buildIpv4UdpDnsPacket(dnsPayload: ByteArray, srcPort: Int = 54321): ByteArray {
        val ipHdrLen = 20
        val udpLen = 8 + dnsPayload.size
        val totalLen = ipHdrLen + udpLen
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45 // version 4, IHL 5
        ChecksumUtils.setIpv4TotalLength(pkt, totalLen)
        pkt[9] = 17 // UDP
        // src ip 10.0.0.2
        pkt[12] = 10; pkt[13] = 0; pkt[14] = 0; pkt[15] = 2
        // dst ip 1.1.1.1
        pkt[16] = 1; pkt[17] = 1; pkt[18] = 1; pkt[19] = 1

        pkt[ipHdrLen] = ((srcPort shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 1] = (srcPort and 0xFF).toByte()
        pkt[ipHdrLen + 2] = 0x00; pkt[ipHdrLen + 3] = 53 // dst port 53
        pkt[ipHdrLen + 4] = ((udpLen shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 5] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsPayload, 0, pkt, ipHdrLen + 8, dnsPayload.size)

        ChecksumUtils.recalcIpv4HeaderChecksum(pkt, ipHdrLen)
        ChecksumUtils.recalcUdpChecksumV4(pkt, ipHdrLen)
        return pkt
    }

    @Test
    fun `plain DNS query for localhost is intercepted and answered via DoH resolver path`() {
        val query = encodeDnsQuery(id = 0xABCD, host = "localhost", qtype = 1)
        val pkt = buildIpv4UdpDnsPacket(query)
        val out = ByteArrayOutputStream()

        // Фейковый резолвер вместо реального сетевого DoH-запроса — тест должен
        // быть детерминированным и не зависеть от сети в CI.
        val fakeResolver: (String) -> List<InetAddress> = { host ->
            if (host == "localhost") listOf(InetAddress.getByName("127.0.0.1")) else emptyList()
        }

        val handled = DnsInterceptor.tryIntercept(
            pkt, pkt.size, 20, out, ipv6 = false, config = BypassConfig(), resolver = fakeResolver
        )
        assertTrue("DNS query on port 53 must always be intercepted", handled)

        val response = out.toByteArray()
        assertTrue("expected a synthesized IP+UDP+DNS response", response.size >= 20 + 8 + 12)

        // DNS header starts after IP(20)+UDP(8) = 28
        val dnsOff = 28
        val respId = ((response[dnsOff].toInt() and 0xFF) shl 8) or (response[dnsOff + 1].toInt() and 0xFF)
        assertEquals(0xABCD, respId)

        val flagsHi = response[dnsOff + 2].toInt() and 0xFF
        assertTrue("QR bit must be set (response)", (flagsHi and 0x80) != 0)

        // src/dst ports must be swapped: response comes "from" port 53
        val respSrcPort = ((response[20].toInt() and 0xFF) shl 8) or (response[21].toInt() and 0xFF)
        assertEquals(53, respSrcPort)
    }

    @Test
    fun `non-DNS UDP traffic (not port 53) is not intercepted`() {
        val ipHdrLen = 20
        val pkt = ByteArray(ipHdrLen + 8 + 4)
        pkt[0] = 0x45
        pkt[9] = 17
        pkt[ipHdrLen + 2] = 0x1F; pkt[ipHdrLen + 3] = 0x90.toByte() // dst port 8080

        val out = ByteArrayOutputStream()
        val handled = DnsInterceptor.tryIntercept(pkt, pkt.size, ipHdrLen, out, ipv6 = false, config = BypassConfig())
        assertTrue("port != 53 must not be treated as DNS", !handled)
        assertEquals(0, out.size())
    }

    @Test
    fun `dnsInterceptEnabled=false disables interception entirely`() {
        val query = encodeDnsQuery(id = 1, host = "localhost", qtype = 1)
        val pkt = buildIpv4UdpDnsPacket(query)
        val out = ByteArrayOutputStream()

        val handled = DnsInterceptor.tryIntercept(
            pkt, pkt.size, 20, out, ipv6 = false,
            config = BypassConfig(dnsInterceptEnabled = false),
            resolver = { emptyList() }
        )
        assertTrue(!handled)
        assertEquals(0, out.size())
    }

    @Test
    fun `malformed DNS query is dropped, not forwarded in plaintext`() {
        val ipHdrLen = 20
        // valid UDP:53 header but garbage/too-short DNS payload
        val garbage = ByteArray(4) // less than the 12-byte DNS header minimum
        val pkt = buildIpv4UdpDnsPacket(garbage)
        val out = ByteArrayOutputStream()

        val handled = DnsInterceptor.tryIntercept(
            pkt, pkt.size, ipHdrLen, out, ipv6 = false, config = BypassConfig(),
            resolver = { emptyList() }
        )
        assertTrue("must report handled=true so caller never forwards the raw plaintext packet", handled)
        assertEquals("must drop rather than leak plaintext DNS", 0, out.size())
    }

    @Test
    fun `resolver failure (empty result) still returns a synthesized NOERROR response, not a leak`() {
        val query = encodeDnsQuery(id = 7, host = "unresolvable.example", qtype = 1)
        val pkt = buildIpv4UdpDnsPacket(query)
        val out = ByteArrayOutputStream()

        val handled = DnsInterceptor.tryIntercept(
            pkt, pkt.size, 20, out, ipv6 = false, config = BypassConfig(),
            resolver = { emptyList() }
        )
        assertTrue(handled)
        // Синтезированный ответ всё равно формируется (ANCOUNT=0), пакет не улетает в открытом виде.
        assertTrue(out.size() > 0)
    }
}
