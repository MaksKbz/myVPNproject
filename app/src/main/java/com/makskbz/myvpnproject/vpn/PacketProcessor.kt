package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.io.OutputStream

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    // === v3.5 CIS statistics ===
    @Volatile private var packetsTotal = 0L
    @Volatile private var packetsSplit = 0L
    @Volatile private var packetsDropped = 0L
    @Volatile private var packetsIpv6 = 0L

    fun getStats(): String = "total=$packetsTotal split=$packetsSplit dropped=$packetsDropped ipv6=$packetsIpv6"

    /**
     * Обрабатывает сырые IP-пакеты и записывает результат в output.
     *
     * v3.0 — исправления:
     * - Принимает BypassConfig для пресет-зависимой обработки
     * - Рандомизированная точка сплита TLS ClientHello (1–5 байт)
     * - Вызывается уже с копией пакета (без race condition)
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
                    // IPv6 header = 40 bytes
                    if (length < 40) { output.write(packetBytes, 0, length); return true }
                    val nextHeader = packetBytes[6].toInt() and 0xFF
                    when (nextHeader) {
                        17 -> handleUdpV6(packetBytes, length, 40, output, config)
                        6  -> handleTcp(packetBytes, length, 40, output, config, ipv6 = true)
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

    private fun handleUdp(pkt: ByteArray, length: Int, ipHdrLen: Int, output: OutputStream, config: BypassConfig = BypassConfig()): Boolean {
        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                    (pkt[ipHdrLen + 3].toInt() and 0xFF)
        return if (dPort == 443) {
            packetsDropped++
            Log.d(TAG, "QUIC/UDP 443 dropped — forcing TCP/TLS fallback [${config.presetName}]")
            true
        } else {
            if (config.udpFakeCount != null && config.udpFakeCount > 0) {
                Log.v(TAG, "UDP fake count=${config.udpFakeCount} – native ciadpi")
            }
            output.write(pkt, 0, length)
            true
        }
    }

    private fun handleUdpV6(pkt: ByteArray, length: Int, ipHdrLen: Int, output: OutputStream, config: BypassConfig): Boolean {
        if (length < ipHdrLen + 8) { output.write(pkt, 0, length); return true }
        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or (pkt[ipHdrLen + 3].toInt() and 0xFF)
        if (dPort == 443) {
            packetsDropped++
            Log.d(TAG, "QUICv6 443 dropped")
            return true
        }
        output.write(pkt, 0, length)
        return true
    }

    private fun handleTcp(
        pkt: ByteArray,
        length: Int,
        ipHdrLen: Int,
        output: OutputStream,
        config: BypassConfig,
        ipv6: Boolean = false
    ): Boolean {
        // IPv6 пока в режиме passthrough — полноценный split будет в v3.6
        if (ipv6) {
            output.write(pkt, 0, length)
            return true
        }

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

        // v3.5 CIS: учитываем пресет
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

        // Disorder / OOB / fake — маркеры для логов, реальная обработка в native ciadpi
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

        packetsSplit++

        // -- Фрагмент 1 --
        val pkt1Len = payloadOffset + frag1PayloadLen
        val pkt1 = pkt.copyOf(pkt1Len)
        if (!ipv6) {
            setIpTotalLength(pkt1, pkt1Len)
            recalcIpChecksum(pkt1, ipHdrLen)
        }
        recalcTcpChecksum(if (ipv6) pkt1 else pkt1, ipHdrLen, tcpHdrLen)

        // -- Фрагмент 2 --
        val pkt2Len = payloadOffset + frag2PayloadLen
        val pkt2 = ByteArray(pkt2Len)
        System.arraycopy(pkt, 0, pkt2, 0, payloadOffset)
        System.arraycopy(pkt, payloadOffset + frag1PayloadLen, pkt2, payloadOffset, frag2PayloadLen)
        advanceTcpSeq(pkt2, ipHdrLen, frag1PayloadLen)
        if (!ipv6) {
            setIpTotalLength(pkt2, pkt2Len)
            recalcIpChecksum(pkt2, ipHdrLen)
        }
        // IPv6 checksum recalc — упрощённо, используем ту же функцию (она возьмёт IPv4 pseudo-header — не идеально, но для теста)
        // TODO v3.6: full IPv6 TCP checksum
        if (!ipv6) {
            recalcTcpChecksum(pkt2, ipHdrLen, tcpHdrLen)
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
        pkt[tcpOffset + 16] = 0; pkt[tcpOffset + 17] = 0
        val pseudo = ByteArray(12 + tcpLen)
        System.arraycopy(pkt, 12, pseudo, 0, 4)
        System.arraycopy(pkt, 16, pseudo, 4, 4)
        pseudo[8]  = 0
        pseudo[9]  = 6
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
