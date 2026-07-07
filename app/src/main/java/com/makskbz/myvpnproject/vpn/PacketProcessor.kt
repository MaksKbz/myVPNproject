package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.io.OutputStream
import kotlin.random.Random

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    // Статистика
    @Volatile private var packetsTotal = 0L
    @Volatile private var packetsSplit = 0L
    @Volatile private var packetsDropped = 0L
    @Volatile private var packetsIpv6 = 0L

    fun getStats(): String = "total=$packetsTotal split=$packetsSplit dropped=$packetsDropped ipv6=$packetsIpv6"

    /**
     * Hybrid DPI bypass v3.4
     * - IPv4 + IPv6 support
     * - Preset-aware split position
     * - SNI-aware split ("1+s")
     * - Disorder mode
     * - OOB / fake markers (logged, partial support)
     * - QUIC blocking
     * - TCP keep-alive & timeout safe
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
                4 -> handleIPv4(packetBytes, length, output, config)
                6 -> { packetsIpv6++; handleIPv6(packetBytes, length, output, config) }
                else -> { output.write(packetBytes, 0, length); true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet v${ipVersion}", e)
            try { output.write(packetBytes, 0, length) } catch (_: Exception) {}
            true
        }
    }

    // ================= IPv4 =================
    private fun handleIPv4(pkt: ByteArray, length: Int, output: OutputStream, config: BypassConfig): Boolean {
        val ipHeaderLen = (pkt[0].toInt() and 0x0F) * 4
        if (ipHeaderLen < 20 || length < ipHeaderLen) {
            output.write(pkt, 0, length); return true
        }
        val protocol = pkt[9].toInt() and 0xFF
        return when (protocol) {
            17 -> handleUdp(pkt, length, ipHeaderLen, output, config)
            6  -> handleTcp(pkt, length, ipHeaderLen, output, config, ipv6 = false)
            1  -> { // ICMP — пропускаем, нужно для MTU discovery
                output.write(pkt, 0, length); true
            }
            else -> { output.write(pkt, 0, length); true }
        }
    }

    // ================= IPv6 =================
    private fun handleIPv6(pkt: ByteArray, length: Int, output: OutputStream, config: BypassConfig): Boolean {
        if (length < 40) { output.write(pkt, 0, length); return true }
        val nextHeader = pkt[6].toInt() and 0xFF
        // IPv6 header = 40 bytes fixed, без опций (упрощённо)
        // Для extension headers нужна более сложная логика — пока пропускаем
        return when (nextHeader) {
            17 -> handleUdpV6(pkt, length, 40, output, config)
            6  -> handleTcp(pkt, length, 40, output, config, ipv6 = true)
            58 -> { output.write(pkt, 0, length); true } // ICMPv6
            else -> { output.write(pkt, 0, length); true }
        }
    }

    // ================= UDP =================
    private fun handleUdp(pkt: ByteArray, length: Int, ipHdrLen: Int, output: OutputStream, config: BypassConfig): Boolean {
        if (length < ipHdrLen + 8) { output.write(pkt, 0, length); return true }
        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or (pkt[ipHdrLen + 3].toInt() and 0xFF)
        // QUIC block → force TCP fallback — ключевой приём против DPI
        if (dPort == 443) {
            packetsDropped++
            Log.d(TAG, "QUIC/UDP 443 dropped — forcing TCP/TLS fallback [${config.presetName}]")
            return true // drop silently
        }
        // UDP fake (если включено в пресете) — пока только лог, реальный fake в ciadpi
        if (config.udpFakeCount != null && config.udpFakeCount > 0) {
            Log.v(TAG, "UDP fake count=${config.udpFakeCount} – handled by native ciadpi")
        }
        output.write(pkt, 0, length)
        return true
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

    // ================= TCP =================
    private fun handleTcp(
        pkt: ByteArray,
        length: Int,
        ipHdrLen: Int,
        output: OutputStream,
        config: BypassConfig,
        ipv6: Boolean
    ): Boolean {
        val srcPort = ((pkt[ipHdrLen].toInt() and 0xFF) shl 8) or (pkt[ipHdrLen + 1].toInt() and 0xFF)
        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or (pkt[ipHdrLen + 3].toInt() and 0xFF)

        // Обрабатываем только исходящий трафик на 443 (Client → Server)
        // Ответы сервера пропускаем как есть
        val isOutbound = dPort == 443
        if (!isOutbound) {
            output.write(pkt, 0, length)
            return true
        }

        val tcpHdrLen = ((pkt[ipHdrLen + 12].toInt() shr 4) and 0x0F) * 4
        if (tcpHdrLen < 20) { output.write(pkt, 0, length); return true }
        val payloadOffset = ipHdrLen + tcpHdrLen
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) { output.write(pkt, 0, length); return true }

        // Проверяем TLS ClientHello
        val tlsInfo = parseTlsClientHello(pkt, payloadOffset, payloadLen)
        if (tlsInfo == null) {
            // Не TLS — пропускаем, но проверяем HTTP Host для mod_http
            if (config.modHttp != null && payloadLen > 4) {
                val head = String(pkt, payloadOffset, minOf(8, payloadLen), Charsets.US_ASCII)
                if (head.startsWith("GET") || head.startsWith("POST") || head.startsWith("HEAD")) {
                    Log.v(TAG, "HTTP detected, modHttp=${config.modHttp}")
                    // TODO: HTTP Host modification — пока заглушка, делает ciadpi
                }
            }
            output.write(pkt, 0, length)
            return true
        }

        packetsSplit++
        Log.i(TAG, "TLS CH detected sni=${tlsInfo.sni ?: \"n/a\"} preset=${config.presetName} v6=$ipv6")

        // ===== Выбор точки сплита по пресету =====
        val splitPos = chooseSplitPosition(config, tlsInfo, payloadLen)
        if (splitPos <= 0 || splitPos >= payloadLen) {
            output.write(pkt, 0, length)
            return true
        }

        val frag1Len = splitPos
        val frag2Len = payloadLen - splitPos

        // Disorder mode?
        val doDisorder = !config.disorderPosition.isNullOrEmpty()
        // OOB mode?
        val doOob = !config.oobPosition.isNullOrEmpty()
        // Fake?
        val doFake = config.fakeEnabled

        if (doFake) Log.v(TAG, "FAKE enabled ttl=${config.fakeTtl} – handled partly by native ciadpi")
        if (doOob) Log.v(TAG, "OOB mode ${config.oobPosition} – marker")
        if (config.dropSack) Log.v(TAG, "dropSack enabled – native ciadpi will apply")
        if (config.tlsRec != null) Log.v(TAG, "tlsRec=${config.tlsRec} – native ciadpi")

        try {
            if (ipv6) {
                splitAndSendIPv6(pkt, length, ipHdrLen, tcpHdrLen, payloadOffset, frag1Len, frag2Len, output, doDisorder)
            } else {
                splitAndSendIPv4(pkt, length, ipHdrLen, tcpHdrLen, payloadOffset, frag1Len, frag2Len, output, doDisorder)
            }
            Log.d(TAG, "Split OK pos=$splitPos disorder=$doDisorder oob=$doOob fake=$doFake")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Split failed, fallback to passthrough", e)
            output.write(pkt, 0, length)
            return true
        }
    }

    // ---- split position chooser ----
    private fun chooseSplitPosition(config: BypassConfig, tlsInfo: TlsInfo?, payloadLen: Int): Int {
        val sp = config.splitPosition ?: return (1..minOf(5, payloadLen -1)).random()
        // Парсим: "1", "1+s", "3", "5", "mids", "end", "sni", etc.
        val baseNum = Regex("""\d+""").find(sp)?.value?.toIntOrNull() ?: 1
        var pos = baseNum.coerceIn(1, payloadLen -1)

        if (sp.contains("s", ignoreCase = true) && tlsInfo?.sniOffset != null) {
            // SNI-aware split: pos = sni_offset + base
            pos = (tlsInfo.sniOffset + baseNum).coerceIn(1, payloadLen -1)
        } else if (sp.contains("m", ignoreCase = true)) {
            // mid
            pos = payloadLen / 2
        } else if (sp.contains("end", ignoreCase = true)) {
            pos = payloadLen - 1
        } else if (sp.contains("r", ignoreCase = true)) {
            // rand
            pos = Random.nextInt(1, payloadLen)
        }
        return pos
    }

    // ---- TLS parser (минимальный, для SNI) ----
    private data class TlsInfo(val sni: String?, val sniOffset: Int?)
    private fun parseTlsClientHello(buf: ByteArray, off: Int, len: Int): TlsInfo? {
        if (len < 9) return null
        val contentType = buf[off].toInt() and 0xFF
        val verMaj = buf[off+1].toInt() and 0xFF
        val verMin = buf[off+2].toInt() and 0xFF
        val hsType = if (len > 5) buf[off+5].toInt() and 0xFF else -1
        if (contentType != 0x16) return null
        if (verMaj != 0x03 || verMin !in 0x01..0x04) return null
        if (hsType != 0x01) return null
        // Попробуем найти SNI
        return try {
            // TLS record header 5 bytes, handshake header 4 bytes
            var p = off + 5 + 4
            if (p + 2 > off + len) return TlsInfo(null, null)
            // client_version 2
            p += 2
            // random 32
            p += 32
            if (p >= off + len) return TlsInfo(null, null)
            // session_id
            val sidLen = buf[p].toInt() and 0xFF
            p += 1 + sidLen
            if (p + 2 > off + len) return TlsInfo(null, null)
            // cipher_suites
            val csLen = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p+1].toInt() and 0xFF)
            p += 2 + csLen
            if (p >= off + len) return TlsInfo(null, null)
            // compression
            val compLen = buf[p].toInt() and 0xFF
            p += 1 + compLen
            if (p + 2 > off + len) return TlsInfo(null, null)
            // extensions
            val extTotalLen = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p+1].toInt() and 0xFF)
            p += 2
            val extEnd = p + extTotalLen
            var sniOffset: Int? = null
            var sniHost: String? = null
            while (p + 4 <= extEnd && p + 4 <= off + len) {
                val extType = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p+1].toInt() and 0xFF)
                val extLen = ((buf[p+2].toInt() and 0xFF) shl 8) or (buf[p+3].toInt() and 0xFF)
                p += 4
                if (extType == 0x0000) { // SNI
                    if (p + 2 <= off + len) {
                        val sniListLen = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p+1].toInt() and 0xFF)
                        var sp = p + 2
                        if (sp + 3 <= off + len) {
                            //val nameType = buf[sp].toInt() and 0xFF
                            val nameLen = ((buf[sp+1].toInt() and 0xFF) shl 8) or (buf[sp+2].toInt() and 0xFF)
                            sp += 3
                            if (sp + nameLen <= off + len) {
                                sniHost = String(buf, sp, nameLen, Charsets.US_ASCII)
                                sniOffset = sp - off
                                break
                            }
                        }
                    }
                }
                p += extLen
                if (p > extEnd) break
            }
            TlsInfo(sniHost, sniOffset)
        } catch (_: Exception) {
            TlsInfo(null, null)
        }
    }

    // ---- IPv4 split ----
    private fun splitAndSendIPv4(
        pkt: ByteArray, length: Int, ipHdrLen: Int, tcpHdrLen: Int,
        payloadOffset: Int, frag1Len: Int, frag2Len: Int,
        output: OutputStream, disorder: Boolean
    ) {
        // fragment 1
        val pkt1Len = payloadOffset + frag1Len
        val pkt1 = pkt.copyOf(pkt1Len)
        setIpTotalLength(pkt1, pkt1Len)
        recalcIpChecksum(pkt1, ipHdrLen)
        recalcTcpChecksum(pkt1, ipHdrLen, tcpHdrLen)

        // fragment 2
        val pkt2Len = payloadOffset + frag2Len
        val pkt2 = ByteArray(pkt2Len)
        System.arraycopy(pkt, 0, pkt2, 0, payloadOffset)
        System.arraycopy(pkt, payloadOffset + frag1Len, pkt2, payloadOffset, frag2Len)
        advanceTcpSeq(pkt2, ipHdrLen, frag1Len)
        setIpTotalLength(pkt2, pkt2Len)
        recalcIpChecksum(pkt2, ipHdrLen)
        recalcTcpChecksum(pkt2, ipHdrLen, tcpHdrLen)

        if (disorder) {
            // Disorder: отправляем второй фрагмент первым
            output.write(pkt2, 0, pkt2Len)
            // маленькая задержка 1-3 мс усиливает эффект disorder
            try { Thread.sleep(1L + Random.nextLong(0,3)) } catch (_: InterruptedException) {}
            output.write(pkt1, 0, pkt1Len)
        } else {
            output.write(pkt1, 0, pkt1Len)
            output.write(pkt2, 0, pkt2Len)
        }
    }

    // ---- IPv6 split ----
    private fun splitAndSendIPv6(
        pkt: ByteArray, length: Int, ipHdrLen: Int, tcpHdrLen: Int,
        payloadOffset: Int, frag1Len: Int, frag2Len: Int,
        output: OutputStream, disorder: Boolean
    ) {
        // IPv6: нет checksum IP, только TCP
        // Payload Length field at bytes 4-5
        fun setIpv6PayloadLen(p: ByteArray, tcpPlusPayload: Int) {
            p[4] = ((tcpPlusPayload shr 8) and 0xFF).toByte()
            p[5] = (tcpPlusPayload and 0xFF).toByte()
        }
        fun recalcTcpChecksumV6(p: ByteArray, ipOff: Int, tcpOff: Int, tcpLen: Int) {
            // TCP checksum over IPv6 pseudo-header
            p[tcpOff + 16] = 0; p[tcpOff + 17] = 0
            // pseudo header: src 16 + dst 16 + tcpLen 4 + zeros 3 + nextHdr 1
            val pseudo = ByteArray(40 + tcpLen)
            System.arraycopy(p, ipOff + 8, pseudo, 0, 16)   // src
            System.arraycopy(p, ipOff + 24, pseudo, 16, 16) // dst
            // tcp length
            pseudo[32] = ((tcpLen shr 24) and 0xFF).toByte()
            pseudo[33] = ((tcpLen shr 16) and 0xFF).toByte()
            pseudo[34] = ((tcpLen shr 8) and 0xFF).toByte()
            pseudo[35] = (tcpLen and 0xFF).toByte()
            // next header
            pseudo[39] = 6
            System.arraycopy(p, tcpOff, pseudo, 40, tcpLen)
            val cs = internetChecksum(pseudo, 0, pseudo.size)
            p[tcpOff + 16] = ((cs shr 8) and 0xFF).toByte()
            p[tcpOff + 17] = (cs and 0xFF).toByte()
        }

        val pkt1Len = payloadOffset + frag1Len
        val pkt1 = pkt.copyOf(pkt1Len)
        setIpv6PayloadLen(pkt1, pkt1Len - ipHdrLen)
        recalcTcpChecksumV6(pkt1, 0, ipHdrLen, pkt1Len - ipHdrLen)

        val pkt2Len = payloadOffset + frag2Len
        val pkt2 = ByteArray(pkt2Len)
        System.arraycopy(pkt, 0, pkt2, 0, payloadOffset)
        System.arraycopy(pkt, payloadOffset + frag1Len, pkt2, payloadOffset, frag2Len)
        advanceTcpSeq(pkt2, ipHdrLen, frag1Len)
        setIpv6PayloadLen(pkt2, pkt2Len - ipHdrLen)
        recalcTcpChecksumV6(pkt2, 0, ipHdrLen, pkt2Len - ipHdrLen)

        if (disorder) {
            output.write(pkt2, 0, pkt2Len)
            try { Thread.sleep(1) } catch (_: InterruptedException) {}
            output.write(pkt1, 0, pkt1Len)
        } else {
            output.write(pkt1, 0, pkt1Len)
            output.write(pkt2, 0, pkt2Len)
        }
    }

    // ============ helpers ============
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
        seq = (seq + delta)
        pkt[seqOffset]     = ((seq ushr 24) and 0xFF).toByte()
        pkt[seqOffset + 1] = ((seq ushr 16) and 0xFF).toByte()
        pkt[seqOffset + 2] = ((seq ushr 8) and 0xFF).toByte()
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
        pseudo[8] = 0; pseudo[9] = 6
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
        if ((length and 1) != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }
}
