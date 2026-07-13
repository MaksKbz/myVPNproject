package com.makskbz.myvpnproject.vpn

import android.content.Context
import org.json.JSONObject

// ==============================================================================
// Модели данных
// ==============================================================================

/**
 * Основная конфигурация приложения.
 * Сериализуется в JSON и хранится в SharedPreferences.
 * Использует только стандартный Android API (без Gson и androidx.preference).
 */
data class BypassConfig(
    val presetName: String = "universal",
    val socksPort: Int = 1080,
    val splitPosition: String? = null,
    val disorderPosition: String? = null,
    val oobPosition: String? = null,
    val fakeEnabled: Boolean = false,
    val fakeTtl: Int = 8,
    val dropSack: Boolean = false,
    val tlsRec: String? = null,
    val modHttp: String? = null,
    val autoMode: String? = null,
    val udpFakeCount: Int? = null,
    val allowedApps: List<String> = emptyList(),
    // v3.6: перехватывать UDP:53 (plain DNS) на уровне TUN и отвечать
    // через DoH — так запрос никогда не выходит на провайдера в открытом
    // виде, даже если приложение игнорирует системный DNS и стучится
    // напрямую в 8.8.8.8:53/77.88.8.8:53 и т.п.
    val dnsInterceptEnabled: Boolean = true
)

/**
 * Пресет — именованный набор CLI-аргументов для ciadpi.
 */
data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val config: BypassConfig
)

// ==============================================================================
// Пресеты (из гайда B4/ByeDPI)
// ==============================================================================

val PRESETS: List<Preset> = listOf(
    Preset(
        id = "universal",
        name = "Универсальный",
        description = "Рекомендуется по умолчанию. Disorder по SNI + OOB + Fake TTL — против ТСПУ/Cloudflare (meduza, youtube).",
        config = BypassConfig(
            presetName = "universal",
            // v3.7.14: усиленный дефолт. Раньше только disorder@1 без SNI-флага
            // и без fake — этого часто не хватало против ТСПУ на Cloudflare
            // (meduza.io и т.п.). Теперь: split/disorder по SNI + OOB + decoy
            // fake-пакет с низким TTL, чтобы DPI увидел «чистый» ClientHello.
            splitPosition = "1+s",
            disorderPosition = "1",
            oobPosition = "1+s",
            fakeEnabled = true,
            fakeTtl = 6,
            dropSack = true,
            tlsRec = "1+s",
            udpFakeCount = 1,
            autoMode = "n"
        )
    ),
    Preset(
        id = "youtube",
        name = "YouTube (агрессивный)",
        description = "Split + Disorder + Drop SACK + OOB + TLS Record split + Fake.",
        config = BypassConfig(
            presetName = "youtube",
            splitPosition = "1",
            disorderPosition = "1",
            oobPosition = "1+s",
            fakeEnabled = true,
            fakeTtl = 8,
            dropSack = true,
            tlsRec = "1+s",
            autoMode = "r,t,s"
        )
    ),
    Preset(
        id = "telegram",
        name = "Telegram",
        description = "Split + Disorder + Fake + HTTP modification.",
        config = BypassConfig(
            presetName = "telegram",
            splitPosition = "1",
            disorderPosition = "1",
            fakeEnabled = true,
            fakeTtl = 8,
            modHttp = "h,d,r",
            autoMode = "t,r,s"
        )
    ),
    Preset(
        id = "minimal",
        name = "Минимальный (экономия батареи)",
        description = "Split + OOB. Минимальное потребление CPU и батареи.",
        config = BypassConfig(
            presetName = "minimal",
            splitPosition = "1",
            oobPosition = "1"
        )
    ),
    Preset(
        id = "aggressive",
        name = "Максимальный (агрессивный)",
        description = "Все методы одновременно. Для упрямых блокировок.",
        config = BypassConfig(
            presetName = "aggressive",
            splitPosition = "1+s",
            disorderPosition = "1+s",
            fakeEnabled = true,
            fakeTtl = 8,
            dropSack = true,
            modHttp = "h,d,r",
            tlsRec = "1+s"
        )
    ),

    // ==========================================================================
    // v3.6 CIS-MAX — пресеты, тюнингованные под конкретных операторов СНГ.
    // TTL для fake-пакетов подобран так, чтобы пакет "умирал" примерно на
    // границе между клиентом и DPI-коробкой оператора, но не долетал до
    // реального сервера назначения — тогда DPI видит валидный (поддельный)
    // ClientHello, а настоящий сервер его никогда не получает.
    // Значения TTL — эмпирические ориентиры по типичной топологии сети
    // оператора (число хопов до пограничного DPI), могут потребовать
    // ручной подстройки под конкретный регион/сегмент сети.
    // ==========================================================================
    Preset(
        id = "kz-telecom",
        name = "Казахтелеком / Kcell (KZ)",
        description = "Тюнинг под ТСПУ-подобные DPI казахстанских операторов: OOB + fake TTL=4 + disorder.",
        config = BypassConfig(
            presetName = "kz-telecom",
            oobPosition = "2",
            disorderPosition = "1",
            fakeEnabled = true,
            fakeTtl = 4,
            dropSack = true,
            autoMode = "n,r"
        )
    ),
    Preset(
        id = "mts-ru",
        name = "МТС (РФ)",
        description = "Split + fake TTL=5 + TLS record split — под DPI МТС.",
        config = BypassConfig(
            presetName = "mts-ru",
            splitPosition = "2",
            disorderPosition = "1",
            fakeEnabled = true,
            fakeTtl = 5,
            dropSack = true,
            tlsRec = "1",
            autoMode = "r,s"
        )
    ),
    Preset(
        id = "beeline-ru",
        name = "Билайн (РФ)",
        description = "OOB по SNI + fake TTL=6 + HTTP-модификация — под DPI Билайна.",
        config = BypassConfig(
            presetName = "beeline-ru",
            oobPosition = "1+s",
            fakeEnabled = true,
            fakeTtl = 6,
            modHttp = "h,d",
            autoMode = "n,t"
        )
    ),
    Preset(
        id = "rostelecom",
        name = "Ростелеком (РФ)",
        description = "Агрессивный набор под ТСПУ Ростелекома: split+disorder+fake TTL=3+drop SACK.",
        config = BypassConfig(
            presetName = "rostelecom",
            splitPosition = "1+s",
            disorderPosition = "1+s",
            fakeEnabled = true,
            fakeTtl = 3,
            dropSack = true,
            tlsRec = "1+s",
            modHttp = "h,d,r",
            autoMode = "r,t,s"
        )
    )
)

/**
 * Порядок для авто-переключения при неудачных проверках соединения.
 * CIS-специфичные пресеты идут после базовых — их предлагаем, если
 * универсальные методы не сработали (или если ASN-детект уже подсказал
 * оператора — тогда его пресет ставится первым, см. BypassVpnService).
 */
val AUTO_SWITCH_ORDER: List<String> = listOf(
    "universal", "youtube", "telegram", "aggressive", "minimal",
    "kz-telecom", "mts-ru", "beeline-ru", "rostelecom"
)

// ==============================================================================
// ConfigManager — хранение без Gson и androidx.preference
// ==============================================================================

object ConfigManager {

    private const val PREFS_NAME = "myvpn_prefs"
    private const val PREF_KEY = "bypass_config_v3"

    fun loadConfig(context: Context): BypassConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREF_KEY, null) ?: return PRESETS[0].config
        return try {
            fromJson(json)
        } catch (e: Exception) {
            PRESETS[0].config
        }
    }

    fun saveConfig(context: Context, config: BypassConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY, toJson(config)).apply()
    }

    fun loadPreset(id: String): BypassConfig =
        PRESETS.find { it.id == id }?.config ?: PRESETS[0].config

    // ── Простая JSON-сериализация через org.json (входит в Android SDK) ──

    private fun toJson(c: BypassConfig): String {
        val o = JSONObject()
        o.put("presetName", c.presetName)
        o.put("socksPort", c.socksPort)
        c.splitPosition?.let    { o.put("splitPosition", it) }
        c.disorderPosition?.let { o.put("disorderPosition", it) }
        c.oobPosition?.let      { o.put("oobPosition", it) }
        o.put("fakeEnabled", c.fakeEnabled)
        o.put("fakeTtl", c.fakeTtl)
        o.put("dropSack", c.dropSack)
        c.tlsRec?.let   { o.put("tlsRec", it) }
        c.modHttp?.let  { o.put("modHttp", it) }
        c.autoMode?.let { o.put("autoMode", it) }
        c.udpFakeCount?.let { o.put("udpFakeCount", it) }
        o.put("dnsInterceptEnabled", c.dnsInterceptEnabled)
        val arr = org.json.JSONArray()
        c.allowedApps.forEach { arr.put(it) }
        o.put("allowedApps", arr)
        return o.toString()
    }

    private fun fromJson(json: String): BypassConfig {
        val o = JSONObject(json)
        val apps = mutableListOf<String>()
        val arr = o.optJSONArray("allowedApps")
        if (arr != null) {
            for (i in 0 until arr.length()) apps.add(arr.getString(i))
        }
        return BypassConfig(
            presetName       = o.optString("presetName", "universal"),
            socksPort        = o.optInt("socksPort", 1080),
            splitPosition    = o.optString("splitPosition").ifEmpty { null },
            disorderPosition = o.optString("disorderPosition").ifEmpty { null },
            oobPosition      = o.optString("oobPosition").ifEmpty { null },
            fakeEnabled      = o.optBoolean("fakeEnabled", false),
            fakeTtl          = o.optInt("fakeTtl", 8),
            dropSack         = o.optBoolean("dropSack", false),
            tlsRec           = o.optString("tlsRec").ifEmpty { null },
            modHttp          = o.optString("modHttp").ifEmpty { null },
            autoMode         = o.optString("autoMode").ifEmpty { null },
            udpFakeCount     = if (o.has("udpFakeCount")) o.getInt("udpFakeCount") else null,
            allowedApps      = apps,
            dnsInterceptEnabled = o.optBoolean("dnsInterceptEnabled", true)
        )
    }

    /**
     * Конвертирует BypassConfig в массив CLI-аргументов для ciadpi.
     */
    fun toCliArgs(config: BypassConfig): Array<String> {
        val args = mutableListOf<String>()
        args += listOf("-p", config.socksPort.toString())
        config.udpFakeCount?.let { args += listOf("-a", it.toString()) }
        config.splitPosition?.let    { args += listOf("-s", it) }
        config.disorderPosition?.let { args += listOf("-d", it) }
        config.oobPosition?.let      { args += listOf("-o", it) }
        if (config.fakeEnabled) {
            args += listOf("-f", "-t", config.fakeTtl.toString())
        }
        config.tlsRec?.let { args += listOf("-r", it) }
        config.modHttp?.let { args += listOf("-M", it) }
        if (config.dropSack) args += "-Y"
        config.autoMode?.let { args += listOf("-A", it) }
        return args.toTypedArray()
    }
}
