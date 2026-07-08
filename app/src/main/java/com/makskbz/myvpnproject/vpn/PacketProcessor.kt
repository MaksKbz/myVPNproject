package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.io.OutputStream

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    // === v3.6 CIS statistics ===
    @Volatile private var packetsTotal = 0L
    @Volatile private var packetsSplit = 0L
    @Volatile private var packetsDropped = 0L
    @Volatile private var packetsIpv6 = 0L
    @Volatile private var packetsSplitIpv6 = 0L

    fun getStats(): String =
        "total=$packetsTotal split=$packetsSplit splitV6=$packetsSplitIpv6 dropped=$packetsDropped ipv6=$packetsIpv6"

    /**
     * Обрабатывает сырые IP-пакеты и записывает результат в output.
     *
     * v3.6 — IPv6 TCP split реализован полностью (был passthrough в v3.5):
     * тот же алгоритм фрагментации TLS ClientHello, что и для IPv4,
     * но с 40-байтным заголовком и честным IPv6 pseudo-header checksum
     * (см. ChecksumUtils.recalcTcpChecksumV6).
     */
    fun processPacket(packetBytes: ByteArray, length: Int, output: OutputStream, config: BypassConfig = BypassConfig()): Boolean {
        packetsTotal++
        if (length < 20) {
            output.write(packetBytes, 0, length)
            return true
        }

        val ipVersion = (packetBytes[0].toInt() shr 4) and 0x0F
        return try {
            when (ipVersion) {
                4 -> {
                    val ipHeaderLen = (packetBytes[0].toInt() and 0x0F) * 4
                    val protocol = packetBytes[9].toInt() and 0xFF
                    when (protocol) {
                        17 -> handleUdp(packetBytes, length, ipHeaderLen, output, config)
                        6  -> handleTcp(packetBytes, length, ipHeaderLen, output, config, ipv6 = false)
                        1  -> { output.write(packetBytes, 0, length); true } // ICMP
                        else -> { output.write(packetBytes, 0, length); true }
                    }
                }
                6 -> {
                    packetsIpv6++
                    // IPv6 header = 40 bytes (расширения заголовков не поддерживаются)
                    if (length < ChecksumUtils.IPV6_HEADER_LEN) { output.write(packetBytes, 0, length); return true }
                    val nextHeader = packetBytes[6].toInt() and 0xFF
                    when (nextHeader) {
                        17 -> handleUdp(packetBytes, length, ChecksumUtils.IPV6_HEADER_LEN, output, config, ipv6 = true)
                        6  -> handleTcp(packetBytes, length, ChecksumUtils.IPV6_HEADER_LEN, output, config, ipv6 = true)
                        58 -> { output.write(packetBytes, 0, length); true } // ICMPv6
                        else -> { output.write(packetBytes, 0, length); true }
                    }
                }
                else -> { output.write(packetBytes, 0, length); true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet v$ipVersion", e)
            try { output.write(packetBytes, 0, length) } catch (_: Exception) {}
            true
        }
    }

    private fun handleUdp(
        pkt: ByteArray,
        length: Int,
        ipHdrLen: Int,
        output: OutputStream,
        config: BypassConfig = BypassConfig(),
        ipv6: Boolean = false
    ): Boolean {
        if (length < ipHdrLen + 4) { output.write(pkt, 0, length); return true }

        // v3.6: перехват plain DNS (UDP:53) → резолв через DoH, чтобы запрос
        // никогда не ушёл провайдеру в открытом виде (см. DnsInterceptor.kt).
        if (DnsInterceptor.tryIntercept(pkt, length, ipHdrLen, output, ipv6, config)) {
            return true
        }

        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                    (pkt[ipHdrLen + 3].toInt() and 0xFF)
        return if (dPort == 443) {
            packetsDropped++
            Log.d(TAG, "QUIC/UDP${if (ipv6) "v6" else ""} 443 dropped — forcing TCP/TLS fallback [${config.presetName}]")
            true
        } else {
            if (config.udpFakeCount != null && config.udpFakeCount > 0) {
                Log.v(TAG, "UDP fake count=${config.udpFakeCount} – native ciadpi")
            }
            output.write(pkt, 0, length)
            true
        }
    }

    /**
     * Универсальная фрагментация TLS ClientHello для IPv4 и IPv6.
     * Разница только в длине IP-заголовка и алгоритме чек-суммы (pseudo-header).
     */
    private fun handleTcp(
        pkt: ByteArray,
        length: Int,
        ipHdrLen: Int,
        output: OutputStream,
        config: BypassConfig,
        ipv6: Boolean = false
    ): Boolean {
        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                    (pkt[ipHdrLen + 3].toInt() and 0xFF)

        if (dPort != 443) {
            output.write(pkt, 0, length)
            return true
        }

        val tcpHdrLen = ((pkt[ipHdrLen + 12].toInt() shr 4) and 0x0F) * 4
        val payloadOffset = ipHdrLen + tcpHdrLen
        val payloadLen = length - payloadOffset

        if (payloadLen < 9) {
            output.write(pkt, 0, length)
            return true
        }

        val contentType   = pkt[payloadOffset].toInt() and 0xFF
        val tlsVerMajor   = pkt[payloadOffset + 1].toInt() and 0xFF
        val tlsVerMinor   = pkt[payloadOffset + 2].toInt() and 0xFF
        val recordLen     = ((pkt[payloadOffset + 3].toInt() and 0xFF) shl 8) or
                            (pkt[payloadOffset + 4].toInt() and 0xFF)
        val handshakeType = pkt[payloadOffset + 5].toInt() and 0xFF

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

        Log.i(TAG, "TLS ClientHello detected — preset=${config.presetName} ipv6=$ipv6")

        val frag1PayloadLen = run {
            val sp = config.splitPosition
            val base = sp?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
            when {
                base in 1 until payloadLen -> base
                sp?.contains("m", true) == true -> payloadLen / 2
                sp?.contains("end", true) == true -> payloadLen - 1
                sp?.contains("r", true) == true -> kotlin.random.Random.nextInt(1, payloadLen)
                else -> (1..minOf(5, payloadLen - 1)).random()
            }
        }.coerceIn(1, payloadLen - 1)
        val frag2PayloadLen = payloadLen - frag1PayloadLen

        val doDisorder = !config.disorderPosition.isNullOrEmpty()
        val doOob = !config.oobPosition.isNullOrEmpty()
        val doFake = config.fakeEnabled
        if (doDisorder) Log.v(TAG, "disorder mode active")
        if (doOob) Log.v(TAG, "oob mode ${config.oobPosition}")
        if (doFake) Log.v(TAG, "fake TTL=${config.fakeTtl}")

        if (frag2PayloadLen <= 0) {
            output.write(pkt, 0, length)
            return true
        }

        if (ipv6) packetsSplitIpv6++ else packetsSplit++

        // -- Фрагмент 1 --
        val pkt1Len = payloadOffset + frag1PayloadLen
        val pkt1 = pkt.copyOf(pkt1Len)
        if (ipv6) {
            ChecksumUtils.setIpv6PayloadLength(pkt1, pkt1Len - ChecksumUtils.IPV6_HEADER_LEN)
            ChecksumUtils.recalcTcpChecksumV6(pkt1, ipHdrLen)
        } else {
            ChecksumUtils.setIpv4TotalLength(pkt1, pkt1Len)
            ChecksumUtils.recalcIpv4HeaderChecksum(pkt1, ipHdrLen)
            ChecksumUtils.recalcTcpChecksumV4(pkt1, ipHdrLen)
        }

        // -- Фрагмент 2 --
        val pkt2Len = payloadOffset + frag2PayloadLen
        val pkt2 = ByteArray(pkt2Len)
        System.arraycopy(pkt, 0, pkt2, 0, payloadOffset)
        System.arraycopy(pkt, payloadOffset + frag1PayloadLen, pkt2, payloadOffset, frag2PayloadLen)
        ChecksumUtils.advanceTcpSeq(pkt2, ipHdrLen, frag1PayloadLen)
        if (ipv6) {
            ChecksumUtils.setIpv6PayloadLength(pkt2, pkt2Len - ChecksumUtils.IPV6_HEADER_LEN)
            ChecksumUtils.recalcTcpChecksumV6(pkt2, ipHdrLen)
        } else {
            ChecksumUtils.setIpv4TotalLength(pkt2, pkt2Len)
            ChecksumUtils.recalcIpv4HeaderChecksum(pkt2, ipHdrLen)
            ChecksumUtils.recalcTcpChecksumV4(pkt2, ipHdrLen)
        }

        // Disorder / split order
        if (doDisorder) {
            // Сначала второй фрагмент, затем первый — сбивает DPI последовательность
            output.write(pkt2, 0, pkt2Len)
            try { Thread.sleep(1L + kotlin.random.Random.nextLong(0, 3)) } catch (_: InterruptedException) {}
            output.write(pkt1, 0, pkt1Len)
        } else {
            output.write(pkt1, 0, pkt1Len)
            output.write(pkt2, 0, pkt2Len)
        }

        Log.d(TAG, "Split: frag1=$frag1PayloadLen frag2=$frag2PayloadLen disorder=$doDisorder oob=$doOob fake=$doFake ipv6=$ipv6")
        return true
    }
}
