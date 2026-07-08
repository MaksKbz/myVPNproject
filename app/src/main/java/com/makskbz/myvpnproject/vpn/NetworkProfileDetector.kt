package com.makskbz.myvpnproject.vpn

import android.net.VpnService
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * NetworkProfileDetector — v3.6 CIS-MAX
 *
 * Определяет провайдера/ASN пользователя, чтобы автоматически подобрать
 * пресет, заточенный под конкретного оператора СНГ (kz-telecom, mts-ru,
 * beeline-ru, rostelecom, ...).
 *
 * Работает через "сырой" TCP-сокет + protect() (обходит сам VPN-туннель,
 * чтобы не зациклиться на себя), делает простой HTTP/1.1 GET на ip-api.com
 * (публичный geo/ASN API, поддерживает обычный HTTP без TLS-стека).
 *
 * ВАЖНО: это лучший доступный сигнал без root/без доступа к таблицам
 * маршрутизации оператора. Если запрос не удался (нет сети, API недоступен,
 * таймаут) — возвращается null, и вызывающий код должен продолжить работу
 * с текущим/дефолтным пресетом.
 */
object NetworkProfileDetector {

    private const val TAG = "NetworkProfileDetector"
    private const val API_HOST = "ip-api.com"
    private const val API_PORT = 80
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 2500

    data class IspProfile(
        val ip: String?,
        val isp: String?,
        val org: String?,
        val asn: String?,
        val countryCode: String?
    )

    /**
     * Карта "подстрока в isp/org/asn (lowercase)" → id пресета.
     * Порядок важен: первое совпадение побеждает.
     */
    private val ISP_PRESET_MAP: List<Pair<String, String>> = listOf(
        "kazakhtelecom" to "kz-telecom",
        "kcell"         to "kz-telecom",
        "aktivator"     to "kz-telecom",
        "as9198"        to "kz-telecom",   // Kazakhtelecom ASN 9198
        "mts"           to "mts-ru",
        "as8359"        to "mts-ru",       // MTS PJSC ASN 8359
        "beeline"       to "beeline-ru",
        "vimpelcom"     to "beeline-ru",
        "as3216"        to "beeline-ru",   // Vympelcom/Beeline ASN 3216 (историч.), реально as8386 тоже beeline
        "as8386"        to "beeline-ru",
        "rostelecom"    to "rostelecom",
        "as12389"       to "rostelecom"    // Rostelecom ASN 12389
    )

    /**
     * Определяет ISP через protect()-нутый сокет, чтобы запрос не ушёл
     * в сам VPN-туннель (иначе будет петля/таймаут).
     *
     * @param vpnService текущий VpnService (для protect())
     * @return IspProfile или null при ошибке/таймауте
     */
    fun detect(vpnService: VpnService): IspProfile? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            // Критично: обходим собственный TUN, иначе запрос зациклится
            // на себя же и никогда не завершится.
            // (Переменная названа isSocketProtected, а не `protected`,
            // чтобы не путать с одноимённым модификатором видимости Kotlin.)
            val isSocketProtected = vpnService.protect(socket)
            if (!isSocketProtected) {
                Log.w(TAG, "protect() failed — skipping ISP detection")
                return null
            }
            socket.connect(InetSocketAddress(API_HOST, API_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val request = "GET /json/?fields=query,isp,org,as,countryCode HTTP/1.1\r\n" +
                    "Host: $API_HOST\r\n" +
                    "User-Agent: myVPNproject/3.6 CIS-ASN-detect\r\n" +
                    "Connection: close\r\n\r\n"
            val out: OutputStream = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()

            val body = readHttpBody(socket)
            if (body.isNullOrBlank()) {
                Log.w(TAG, "Empty response from ip-api.com")
                return null
            }
            parseJsonProfile(body)
        } catch (e: Exception) {
            Log.w(TAG, "ISP detection failed: ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpBody(socket: Socket): String? {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        // Пропускаем HTTP-заголовки
        var line: String?
        var chunked = false
        while (true) {
            line = reader.readLine() ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Transfer-Encoding", ignoreCase = true) &&
                line.contains("chunked", ignoreCase = true)
            ) chunked = true
        }
        val sb = StringBuilder()
        if (chunked) {
            // Простой парсер chunked-тела (ip-api обычно шлёт Content-Length,
            // но на всякий случай поддержим и chunked)
            while (true) {
                val sizeLine = reader.readLine() ?: break
                val size = sizeLine.trim().toIntOrNull(16) ?: break
                if (size <= 0) break
                val buf = CharArray(size)
                var readTotal = 0
                while (readTotal < size) {
                    val n = reader.read(buf, readTotal, size - readTotal)
                    if (n < 0) break
                    readTotal += n
                }
                sb.append(buf, 0, readTotal)
                reader.readLine() // trailing CRLF
            }
        } else {
            var c: Int
            while (reader.read().also { c = it } != -1) {
                sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    /**
     * Минимальный ручной JSON-парсер (без org.json.JSONObject-зависимостей
     * от полноценного парсера строк с экранированием — ответ ip-api.com
     * простой плоский объект, этого достаточно).
     */
    private fun parseJsonProfile(json: String): IspProfile {
        fun field(name: String): String? {
            val regex = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")
            return regex.find(json)?.groupValues?.get(1)?.ifBlank { null }
        }
        return IspProfile(
            ip          = field("query"),
            isp         = field("isp"),
            org         = field("org"),
            asn         = field("as"),
            countryCode = field("countryCode")
        )
    }

    /**
     * Подбирает id пресета под известного оператора СНГ.
     * Возвращает null, если оператор не распознан — вызывающий код
     * должен продолжить с текущим/универсальным пресетом.
     */
    fun presetForIsp(profile: IspProfile?): String? {
        if (profile == null) return null
        val haystack = listOfNotNull(profile.isp, profile.org, profile.asn)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return null
        for ((needle, presetId) in ISP_PRESET_MAP) {
            if (haystack.contains(needle)) {
                Log.i(TAG, "ISP matched '$needle' -> preset=$presetId (isp=${profile.isp}, as=${profile.asn})")
                return presetId
            }
        }
        Log.i(TAG, "ISP not recognized as CIS-tuned operator: isp=${profile.isp} as=${profile.asn}")
        return null
    }
}
