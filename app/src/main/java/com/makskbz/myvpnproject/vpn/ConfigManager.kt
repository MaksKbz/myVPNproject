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
    val allowedApps: List<String> = emptyList()
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
        description = "Рекомендуется по умолчанию. OOB по SNI + Disorder + авто-переключение.",
        config = BypassConfig(
            presetName = "universal",
            udpFakeCount = 1,
            autoMode = "n",
            oobPosition = "1+s",
            disorderPosition = "1",
            fakeEnabled = false
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
    )
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
            allowedApps      = apps
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
