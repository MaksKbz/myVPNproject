package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * DnsInterceptor — v3.6 CIS-MAX
 *
 * Роадмап п.3 "DoH-клиент (не только DNS-серверы)":
 * До v3.6 приложение только ПОДСКАЗЫВАЛО системе адреса DoH-совместимых
 * DNS-серверов (`addDnsServer("1.1.1.1")` в BypassVpnService) — но если
 * приложение-клиент (или сама ОС) всё равно слало обычный UDP:53-запрос,
 * он уходил в открытом виде и провайдер/РКН мог его увидеть и подменить
 * (DNS-spoofing — самый дешёвый способ блокировки в РФ/СНГ).
 *
 * Начиная с v3.6 мы перехватываем ЛЮБОЙ UDP-пакет с портом назначения 53
 * прямо на уровне TUN, разбираем DNS-запрос вручную, резолвим домен через
 * уже существующий DohResolver (HTTPS-транспорт, см. DohResolver.kt) и
 * синтезируем корректный DNS-ответ, который "отправляем" обратно в TUN
 * так, будто он пришёл от исходного DNS-сервера. Реальный UDP:53-пакет
 * наружу никогда не уходит.
 *
 * Если запрос не удаётся распарсить/зарезолвить — по умолчанию пакет
 * дропается (не уходит в открытом виде), чтобы не допустить утечки DNS.
 */
object DnsInterceptor {

    private const val TAG = "DnsInterceptor"
    private const val DNS_PORT = 53
    private const val DEFAULT_TTL_SECONDS = 60

    /**
     * Пытается перехватить DNS-запрос. Возвращает true, если пакет был
     * обработан (перехвачен и синтезирован ответ, либо намеренно
     * отброшен) — в этом случае вызывающий код НЕ должен пересылать
     * исходный пакет дальше. Возвращает false, если это не DNS-запрос
     * (например, порт не 53) — тогда пакет обрабатывается как обычно.
     *
     * @param resolver функция резолва хоста в адреса. По умолчанию —
     *   реальный DohResolver.resolve (сетевой DoH-запрос). Юнит-тесты
     *   подставляют фейковый резолвер, чтобы не зависеть от сети и не
     *   рисковать флаки/медленным CI (см. DnsInterceptorTest.kt).
     */
    fun tryIntercept(
        pkt: ByteArray,
        length: Int,
        ipHdrLen: Int,
        output: OutputStream,
        ipv6: Boolean,
        config: BypassConfig,
        resolver: (String) -> List<InetAddress> = DohResolver::resolve
    ): Boolean {
        if (!config.dnsInterceptEnabled) return false
        if (length < ipHdrLen + 8) return false

        val dPort = ((pkt[ipHdrLen + 2].toInt() and 0xFF) shl 8) or (pkt[ipHdrLen + 3].toInt() and 0xFF)
        if (dPort != DNS_PORT) return false

        val udpLen = ((pkt[ipHdrLen + 4].toInt() and 0xFF) shl 8) or (pkt[ipHdrLen + 5].toInt() and 0xFF)
        val dnsOffset = ipHdrLen + 8
        val dnsLen = minOf(udpLen - 8, length - dnsOffset)
        if (dnsLen < 12) {
            Log.w(TAG, "DNS packet too short, dropping to avoid plaintext leak")
            return true
        }

        return try {
            val query = pkt.copyOfRange(dnsOffset, dnsOffset + dnsLen)
            val parsed = parseQuestion(query)
            if (parsed == null) {
                Log.w(TAG, "Unparseable DNS query, dropping")
                return true
            }
            val (host, qType, questionBytesLen) = parsed
            Log.d(TAG, "Intercepted DNS query: $host type=$qType via DoH")

            val addresses: List<InetAddress> = try {
                resolver(host)
            } catch (e: Exception) {
                Log.w(TAG, "DoH resolve failed for $host: ${e.message}")
                emptyList()
            }

            val answerRecords = buildAnswerRecords(addresses, qType)
            val responseDns = buildDnsResponse(query, questionBytesLen, answerRecords)

            val responsePkt = wrapAsDnsResponse(pkt, ipHdrLen, responseDns, ipv6)
            output.write(responsePkt, 0, responsePkt.size)
            true
        } catch (e: Exception) {
            Log.e(TAG, "DNS intercept failed, dropping query to avoid leak", e)
            true
        }
    }


    // ─────────────────────────── DNS parsing / synthesis ───────────────────────────

    /** @return Triple(hostname, qtype, byteLengthOfQuestionSection) or null on parse failure. */
    private fun parseQuestion(query: ByteArray): Triple<String, Int, Int>? {
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) { pos += 1; break }
            if (len and 0xC0 == 0xC0) return null // compression in query — не ожидаем, безопаснее отказаться
            pos += 1
            if (pos + len > query.size) return null
            labels.add(String(query, pos, len, Charsets.US_ASCII))
            pos += len
        }
        if (pos + 4 > query.size) return null
        val qType = ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF)
        pos += 4 // qtype(2) + qclass(2)

        val host = labels.joinToString(".")
        if (host.isEmpty()) return null
        return Triple(host, qType, pos - 12)
    }

    private fun buildAnswerRecords(addresses: List<InetAddress>, qType: Int): ByteArray {
        val wantV4 = qType == 1  // A
        val wantV6 = qType == 28 // AAAA
        val matching = addresses.filter {
            (wantV4 && it is Inet4Address) || (wantV6 && it is Inet6Address)
        }
        if (matching.isEmpty()) return ByteArray(0)

        val recSize = { addr: InetAddress -> 2 + 2 + 2 + 4 + 2 + addr.address.size }
        val total = matching.sumOf { recSize(it) }
        val out = ByteArray(total)
        var pos = 0
        for (addr in matching) {
            // NAME = pointer to offset 12 (question name)
            out[pos] = 0xC0.toByte(); out[pos + 1] = 0x0C; pos += 2
            val type = if (addr is Inet4Address) 1 else 28
            out[pos] = ((type shr 8) and 0xFF).toByte(); out[pos + 1] = (type and 0xFF).toByte(); pos += 2
            out[pos] = 0x00; out[pos + 1] = 0x01 // CLASS = IN
            pos += 2
            // TTL
            out[pos] = ((DEFAULT_TTL_SECONDS shr 24) and 0xFF).toByte()
            out[pos + 1] = ((DEFAULT_TTL_SECONDS shr 16) and 0xFF).toByte()
            out[pos + 2] = ((DEFAULT_TTL_SECONDS shr 8) and 0xFF).toByte()
            out[pos + 3] = (DEFAULT_TTL_SECONDS and 0xFF).toByte()
            pos += 4
            val rdata = addr.address
            out[pos] = ((rdata.size shr 8) and 0xFF).toByte(); out[pos + 1] = (rdata.size and 0xFF).toByte()
            pos += 2
            System.arraycopy(rdata, 0, out, pos, rdata.size)
            pos += rdata.size
        }
        return out
    }

    private fun buildDnsResponse(query: ByteArray, questionBytesLen: Int, answerRecords: ByteArray): ByteArray {
        val ancount = if (answerRecords.isEmpty()) 0 else {
            // подсчитываем число записей по факту (каждая запись переменной длины,
            // но с фиксированной IP-адресной частью — пересчитываем проходом)
            countRecords(answerRecords)
        }
        val header = ByteArray(12)
        header[0] = query[0]; header[1] = query[1] // ID эхо
        val rd = query[2].toInt() and 0x01
        header[2] = (0x80 or rd).toByte() // QR=1 (response), Opcode=0, AA=0, TC=0, RD=echo
        header[3] = 0x80.toByte() // RA=1, Z=0, RCODE=0 (NOERROR)
        header[4] = 0x00; header[5] = 0x01 // QDCOUNT = 1
        header[6] = ((ancount shr 8) and 0xFF).toByte(); header[7] = (ancount and 0xFF).toByte()
        header[8] = 0x00; header[9] = 0x00  // NSCOUNT = 0
        header[10] = 0x00; header[11] = 0x00 // ARCOUNT = 0

        val questionSection = query.copyOfRange(12, 12 + questionBytesLen)
        return header + questionSection + answerRecords
    }

    private fun countRecords(records: ByteArray): Int {
        var pos = 0
        var count = 0
        while (pos < records.size) {
            pos += 2 // name pointer
            pos += 2 // type
            pos += 2 // class
            pos += 4 // ttl
            val rdlen = ((records[pos].toInt() and 0xFF) shl 8) or (records[pos + 1].toInt() and 0xFF)
            pos += 2 + rdlen
            count++
        }
        return count
    }

    // ─────────────────────────── IP/UDP wrapping ───────────────────────────

    /**
     * Оборачивает синтезированный DNS-ответ в IP+UDP пакет с переставленными
     * местами src/dst (ответ идёт "от" исходного DNS-сервера "к" исходному
     * клиенту внутри TUN).
     */
    private fun wrapAsDnsResponse(originalPkt: ByteArray, ipHdrLen: Int, dnsPayload: ByteArray, ipv6: Boolean): ByteArray {
        val udpLen = 8 + dnsPayload.size
        val totalLen = ipHdrLen + udpLen
        val out = ByteArray(totalLen)
        System.arraycopy(originalPkt, 0, out, 0, ipHdrLen)

        if (ipv6) {
            // swap src(8..23) / dst(24..39)
            val src = out.copyOfRange(8, 24)
            val dst = out.copyOfRange(24, 40)
            System.arraycopy(dst, 0, out, 8, 16)
            System.arraycopy(src, 0, out, 24, 16)
            ChecksumUtils.setIpv6PayloadLength(out, udpLen)
        } else {
            // swap src(12..15) / dst(16..19)
            val src = out.copyOfRange(12, 16)
            val dst = out.copyOfRange(16, 20)
            System.arraycopy(dst, 0, out, 12, 4)
            System.arraycopy(src, 0, out, 16, 4)
            ChecksumUtils.setIpv4TotalLength(out, totalLen)
        }

        val udpOff = ipHdrLen
        // swap ports: dst becomes original src port, src becomes 53
        val origSrcPort0 = originalPkt[udpOff]; val origSrcPort1 = originalPkt[udpOff + 1]
        out[udpOff] = 0x00; out[udpOff + 1] = DNS_PORT.toByte()          // src port = 53
        out[udpOff + 2] = origSrcPort0; out[udpOff + 3] = origSrcPort1   // dst port = original src port
        out[udpOff + 4] = ((udpLen shr 8) and 0xFF).toByte()
        out[udpOff + 5] = (udpLen and 0xFF).toByte()
        out[udpOff + 6] = 0; out[udpOff + 7] = 0 // checksum placeholder

        System.arraycopy(dnsPayload, 0, out, udpOff + 8, dnsPayload.size)

        if (ipv6) {
            ChecksumUtils.recalcUdpChecksumV6(out, ipHdrLen)
        } else {
            ChecksumUtils.recalcIpv4HeaderChecksum(out, ipHdrLen)
            ChecksumUtils.recalcUdpChecksumV4(out, ipHdrLen)
        }
        return out
    }
}
