package com.makskbz.myvpnproject.vpn

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ==============================================================================
// Модели данных
// ==============================================================================

/**
 * Основная конфигурация приложения.
 * Сериализуется в JSON и хранится в SharedPreferences.
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
            // CLI: -Ku -a1 -An -o1+s -At,r,s -d1
            udpFakeCount = 1,
            autoMode = "n",             // -An: авто при отсутствии блокировки
            oobPosition = "1+s",         // -o1+s: OOB после SNI
            disorderPosition = "1",      // -d1: disorder на 1 байт
            fakeEnabled = false
        )
    ),
    Preset(
        id = "youtube",
        name = "YouTube (агрессивный)",
        description = "Split + Disorder + Drop SACK + OOB + TLS Record split + Fake.",
        config = BypassConfig(
            presetName = "youtube",
            // CLI: -s1 -q1 -Y -Ar -s5 -o1+s -At -f-1 -r1+s -As
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
            // CLI: -s1 -d1 -f -t8 -At,r,s -M h,d,r
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
            // CLI: -s1 -o1
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
            // CLI: -s1+s -d1+s -f -t8 -Y -M h,d,r -r1+s
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
// ConfigManager
// ==============================================================================

object ConfigManager {

    private const val PREF_KEY = "bypass_config_v3"
    private val gson = Gson()

    fun loadConfig(context: Context): BypassConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString(PREF_KEY, null) ?: return PRESETS[0].config
        return try {
            gson.fromJson(json, BypassConfig::class.java)
        } catch (e: Exception) {
            PRESETS[0].config
        }
    }

    fun saveConfig(context: Context, config: BypassConfig) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(PREF_KEY, gson.toJson(config)).apply()
    }

    fun loadPreset(id: String): BypassConfig =
        PRESETS.find { it.id == id }?.config ?: PRESETS[0].config

    /**
     * Конвертирует BypassConfig в массив CLI-аргументов для ciadpi.
     * Порядок соответствует документации ByeDPI.
     */
    fun toCliArgs(config: BypassConfig): Array<String> {
        val args = mutableListOf<String>()

        // Порт SOCKS5
        args += listOf("-p", config.socksPort.toString())

        // UDP: фейковые пакеты (для QUIC-обхода)
        config.udpFakeCount?.let { args += listOf("-a", it.toString()) }

        // Фрагментация
        config.splitPosition?.let    { args += listOf("-s", it) }
        config.disorderPosition?.let { args += listOf("-d", it) }
        config.oobPosition?.let      { args += listOf("-o", it) }

        // Fake-пакет
        if (config.fakeEnabled) {
            args += listOf("-f", "-t", config.fakeTtl.toString())
        }

        // TLS Record Split
        config.tlsRec?.let { args += listOf("-r", it) }

        // HTTP-модификация
        config.modHttp?.let { args += listOf("-M", it) }

        // Drop SACK
        if (config.dropSack) args += "-Y"

        // Auto-mode
        config.autoMode?.let { args += listOf("-A", it) }

        return args.toTypedArray()
    }
}
