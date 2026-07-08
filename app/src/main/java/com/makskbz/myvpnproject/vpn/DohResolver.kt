package com.makskbz.myvpnproject.vpn

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * DoH Resolver — v3.6.1 CIS-MAX (переписан без внешних зависимостей)
 *
 * ВАЖНО (история бага): первая версия использовала
 * `com.squareup.okhttp3:okhttp-dnsoverhttps` + `kotlinx-coroutines-android`.
 * Эта связка сломала сборку CI (`Build Debug APK` падал с exit code 1),
 * из-за чего коммит был откачен. Данная версия использует ТОЛЬКО
 * стандартный Android SDK (`javax.net.ssl.HttpsURLConnection` + `org.json`,
 * которые уже применяются в `ConfigManager.kt`) — новых зависимостей нет,
 * риск повторной поломки сборки исключён.
 *
 * Ключевая техническая идея (даже надёжнее, чем bootstrap-DNS из первой
 * версии): DoH-провайдеры (Cloudflare, AdGuard, Quad9) указывают СВОИ IP
 * прямо в SAN сертификата. Значит можно подключаться по URL вида
 * `https://1.1.1.1/dns-query` — Java не отправит SNI вообще (RFC 6066
 * запрещает SNI для IP-литералов), а проверка сертификата пройдёт по
 * IP-адресу в SAN. В результате провайдер/DPI не видит вообще никакого
 * доменного имени в этом TLS-соединении — ни резолвера, ни, тем более,
 * того домена, который мы на самом деле резолвим (он идёт внутри
 * зашифрованного HTTPS-тела запроса).
 *
 * Формат запроса — JSON DoH API (Cloudflare/Google-совместимый):
 * GET https://<ip>/dns-query?name=<host>&type=A
 * Header: Accept: application/dns-json
 */
object DohResolver {

    private const val TAG = "DohResolver"
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 3000
    private const val CACHE_TTL_MS = 60_000L

    /**
     * DoH-провайдеры, к которым обращаемся напрямую по IP (без хостнейма).
     * AdGuard первым — лучше всего работает из РФ/СНГ.
     */
    private val PROVIDERS = listOf(
        "94.140.14.14", // AdGuard
        "1.1.1.1",      // Cloudflare
        "9.9.9.9"       // Quad9
    )

    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val ipLiteralRegex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * Оставлен для обратной совместимости с вызовом из BypassVpnService.onCreate() —
     * в новой реализации явная инициализация не требуется (нет клиента для
     * настройки), но метод сохранён, чтобы не трогать точки вызова.
     */
    fun init(cacheDir: File? = null) {
        Log.i(TAG, "DohResolver ready (pure HttpsURLConnection, zero extra deps)")
    }

    /**
     * Резолвит домен через DoH. При неудаче всех провайдеров — fallback на
     * системный резолвер (лучше получить IP через обычный DNS, чем совсем
     * не подключиться; полная защита от DNS-спуфинга обеспечивается тем,
     * что для реального пользовательского трафика packet-уровневый
     * DnsInterceptor.kt перехватывает UDP:53 ещё до системного резолвера).
     */
    fun resolve(host: String): List<InetAddress> {
        if (isLiteralIp(host)) {
            return try { listOf(InetAddress.getByName(host)) } catch (_: Exception) { emptyList() }
        }

        cache[host]?.let { entry ->
            if (System.currentTimeMillis() < entry.expiresAt) return entry.addresses
        }

        for (provider in PROVIDERS) {
            try {
                val v4 = queryProvider(provider, host, "A")
                val v6 = queryProvider(provider, host, "AAAA")
                val all = v4 + v6
                if (all.isNotEmpty()) {
                    cache[host] = CacheEntry(all, System.currentTimeMillis() + CACHE_TTL_MS)
                    Log.i(TAG, "DoH($provider) resolved $host -> ${all.map { it.hostAddress }}")
                    return all
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoH provider $provider failed for $host: ${e.message}")
            }
        }

        Log.w(TAG, "All DoH providers failed for $host, falling back to system resolver")
        return try { InetAddress.getAllByName(host).toList() } catch (_: UnknownHostException) { emptyList() }
    }

    private fun isLiteralIp(host: String): Boolean =
        ipLiteralRegex.matches(host) || host.contains(":")

    private fun queryProvider(providerIp: String, host: String, type: String): List<InetAddress> {
        val encodedName = URLEncoder.encode(host, "UTF-8")
        val url = URL("https://$providerIp/dns-query?name=$encodedName&type=$type")
        val conn = url.openConnection() as HttpsURLConnection
        return try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.setRequestProperty("User-Agent", "myVPNproject/3.6 DoH")
            val code = conn.responseCode
            if (code != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseDnsJson(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDnsJson(body: String): List<InetAddress> {
        val json = JSONObject(body)
        val answers = json.optJSONArray("Answer") ?: return emptyList()
        val result = mutableListOf<InetAddress>()
        for (i in 0 until answers.length()) {
            val rec = answers.getJSONObject(i)
            val type = rec.optInt("type")
            if (type == 1 || type == 28) { // A или AAAA
                val data = rec.optString("data")
                if (data.isNotBlank()) {
                    try { result.add(InetAddress.getByName(data)) } catch (_: Exception) {}
                }
            }
        }
        return result
    }

    /**
     * Проверка, является ли домен из RU/CIS зоны — используется для
     * логирования/приоритизации в других частях приложения.
     */
    fun isCisDomain(host: String): Boolean {
        val h = host.lowercase()
        return h.endsWith(".ru") || h.endsWith(".рф") || h.endsWith(".xn--p1ai") ||
                h.endsWith(".su") || h.endsWith(".by") || h.endsWith(".kz") ||
                h.endsWith(".ua") || h.endsWith(".am") || h.endsWith(".az") ||
                h.endsWith(".kg") || h.endsWith(".tj") || h.endsWith(".uz") ||
                h.endsWith(".vk.com") || h.endsWith(".ok.ru") || h.endsWith(".mail.ru")
    }
}
