package com.makskbz.myvpnproject.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DoH Resolver — защита от DNS-спуфинга РКН / провайдеров СНГ
 *
 * Использует несколько DoH-провайдеров с fallback:
 * 1. Cloudflare (1.1.1.1) — глобальный
 * 2. Google (8.8.8.8)
 * 3. AdGuard (94.140.14.14) — работает в РФ
 * 4. Quad9 (9.9.9.9)
 *
 * Для .ru / .рф / .su / .kz зон — дополнительно пробуем Yandex DoH
 */
object DohResolver {

    private const val TAG = "DohResolver"

    @Volatile private var dohClient: OkHttpClient? = null
    @Volatile private var dohDns: Dns? = null

    fun init(cacheDir: File) {
        if (dohDns != null) return
        try {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .cache(Cache(File(cacheDir, "doh_cache"), 5L * 1024 * 1024))
                .build()

            // Список DoH endpoints — приоритет для СНГ
            val dohUrls = listOf(
                // AdGuard — отлично работает в РФ, не блокируется
                "https://dns.adguard-dns.com/dns-query",
                // Cloudflare
                "https://1.1.1.1/dns-query",
                "https://1.0.0.1/dns-query",
                // Google
                "https://dns.google/dns-query",
                // Quad9
                "https://dns.quad9.net/dns-query"
                // Yandex DoH — https://common.dot.dns.yandex.net/dns-query
                // (включим как fallback, иногда блокируется)
            )

            // Bootstrap DNS — IP адреса, чтобы не зависеть от системного DNS
            val bootstrapDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        // Хардкод bootstrap IP для DoH-хостов
                        when (hostname) {
                            "dns.adguard-dns.com" -> listOf(InetAddress.getByName("94.140.14.14"), InetAddress.getByName("94.140.15.15")),
                            "1.1.1.1", "one.one.one.one", "cloudflare-dns.com" ->
                                listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")),
                            "dns.google" ->
                                listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")),
                            "dns.quad9.net" ->
                                listOf(InetAddress.getByName("9.9.9.9")),
                            "common.dot.dns.yandex.net" ->
                                listOf(InetAddress.getByName("77.88.8.8"), InetAddress.getByName("77.88.8.1")),
                            else -> Dns.SYSTEM.lookup(hostname)
                        }
                    } catch (e: Exception) {
                        try { Dns.SYSTEM.lookup(hostname) } catch (_: Exception) { emptyList() }
                    }
                }
            }

            // Пробуем каждый DoH endpoint
            var lastErr: Exception? = null
            for (url in dohUrls) {
                try {
                    val doh = DnsOverHttps.Builder()
                        .client(bootstrapClient)
                        .url(url.toHttpUrl())
                        .bootstrapDnsHosts(
                            when {
                                url.contains("adguard") -> listOf(
                                    InetAddress.getByName("94.140.14.14"),
                                    InetAddress.getByName("94.140.15.15"),
                                    InetAddress.getByName("2a10:50c0::ad1:ff"),
                                    InetAddress.getByName("2a10:50c0::ad2:ff")
                                )
                                url.contains("1.1.1.1") -> listOf(
                                    InetAddress.getByName("1.1.1.1"),
                                    InetAddress.getByName("1.0.0.1"),
                                    InetAddress.getByName("2606:4700:4700::1111"),
                                    InetAddress.getByName("2606:4700:4700::1001")
                                )
                                url.contains("google") -> listOf(
                                    InetAddress.getByName("8.8.8.8"),
                                    InetAddress.getByName("8.8.4.4")
                                )
                                url.contains("quad9") -> listOf(
                                    InetAddress.getByName("9.9.9.9"),
                                    InetAddress.getByName("149.112.112.112")
                                )
                                else -> emptyList()
                            }
                        )
                        .includeIPv6(true)
                        .build()
                    // Тестовый lookup
                    val test = doh.lookup("google.com")
                    if (test.isNotEmpty()) {
                        dohDns = doh
                        dohClient = bootstrapClient
                        Log.i(TAG, "DoH initialized: $url → ${test.first().hostAddress}")
                        return
                    }
                } catch (e: Exception) {
                    lastErr = e
                    Log.w(TAG, "DoH $url failed: ${e.message}")
                    continue
                }
            }
            Log.e(TAG, "All DoH endpoints failed, fallback to SYSTEM", lastErr)
            dohDns = Dns.SYSTEM
        } catch (e: Exception) {
            Log.e(TAG, "DoH init fatal", e)
            dohDns = Dns.SYSTEM
        }
    }

    fun resolve(host: String): List<InetAddress> {
        val dns = dohDns ?: return try { Dns.SYSTEM.lookup(host) } catch (e: UnknownHostException) { emptyList() }
        return try {
            dns.lookup(host)
        } catch (e: Exception) {
            Log.w(TAG, "DoH lookup failed for $host: ${e.message}, fallback SYSTEM")
            try { Dns.SYSTEM.lookup(host) } catch (_: Exception) { emptyList() }
        }
    }

    suspend fun resolveAsync(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        resolve(host)
    }

    /**
     * Проверка, является ли домен из RU/CIS зоны — для таких
     * приоритет отдаём Yandex DNS, т.к. он быстрее внутри РФ.
     */
    fun isCisDomain(host: String): Boolean {
        val h = host.lowercase()
        return h.endsWith(".ru") || h.endsWith(".рф") || h.endsWith(".xn--p1ai") ||
                h.endsWith(".su") || h.endsWith(".by") || h.endsWith(".kz") ||
                h.endsWith(".ua") || h.endsWith(".am") || h.endsWith(".az") ||
                h.endsWith(".kg") || h.endsWith(".tj") || h.endsWith(".uz") ||
                h.endsWith(".yandex.ru") || h.endsWith(".yandex.com") ||
                h.endsWith(".vk.com") || h.endsWith(".ok.ru") || h.endsWith(".mail.ru")
    }

    fun getOkHttpClientWithDoh(cacheDir: File): OkHttpClient {
        init(cacheDir)
        return OkHttpClient.Builder()
            .dns(dohDns ?: Dns.SYSTEM)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
