package com.makskbz.myvpnproject

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makskbz.myvpnproject.vpn.BypassVpnService
import com.makskbz.myvpnproject.vpn.ConfigManager
import com.makskbz.myvpnproject.vpn.CrashLogger
import com.makskbz.myvpnproject.vpn.PRESETS
import com.makskbz.myvpnproject.vpn.ProxyEngine

data class AppItem(val name: String, val packageName: String)

class MainActivity : ComponentActivity() {

    private val VPN_REQUEST_CODE = 1001
    private val NOTIF_PERMISSION_CODE = 2001
    private var selectedPackages = mutableStateListOf<String>()
    private var selectedPresetId = mutableStateOf("universal")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v3.7.3 CIS-MAX: устанавливаем сборщик крашей (Kotlin + нативный
        // SIGABRT/SIGSEGV/...) КАК МОЖНО РАНЬШЕ — до любых других вызовов,
        // чтобы даже краш при самом первом нажатии "Запустить VPN" был
        // перехвачен и сохранён на диск. Это позволяет диагностировать
        // краши на устройствах без доступа к `adb logcat` — пользователь
        // видит причину прямо в приложении (вкладка "Справка" ниже).
        CrashLogger.install(this)
        ProxyEngine.installCrashHandler(this)
        CrashLogger.checkpoint(this, "MainActivity.onCreate() — приложение запущено")

        // Исправление #1 (КРИТИЧЕСКОЕ): запрашиваем POST_NOTIFICATIONS на Android 13+
        // Без этого foreground-уведомление не появится и система может убить сервис.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_CODE
                )
            }
        }

        val savedConfig = ConfigManager.loadConfig(this)
        selectedPresetId.value = savedConfig.presetName
        selectedPackages.addAll(savedConfig.allowedApps)

        val installedApps = getInstalledAppsList()

        // Читаем логи прошлого краша (если приложение вылетело в предыдущем
        // запуске) — показываем их пользователю прямо в UI, чтобы не нужен
        // был adb. Копируем в переменные ДО clearAll(), иначе покажем пустоту.
        val javaCrash = CrashLogger.readJavaCrashLog(this)
        val nativeCrash = CrashLogger.readNativeCrashLog(this)
        // v3.7.5 CIS-MAX: лог Kotlin-контрольных точек — критичен для
        // диагностики SIGKILL (агрессивные OEM-watchdog'и вроде ColorOS
        // Autostart Manager), который не перехватывается ни Java, ни C
        // обработчиками. Последняя записанная точка = последний живой шаг.
        val checkpointsLog = CrashLogger.readCheckpointLog(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        installedApps    = installedApps,
                        selectedPackages = selectedPackages,
                        selectedPresetId = selectedPresetId,
                        onStartVpn       = { requestVpnPermission() },
                        onStopVpn        = { stopVpnService() },
                        onSaveConfig     = { presetId ->
                            selectedPresetId.value = presetId
                            val cfg = ConfigManager.loadPreset(presetId)
                                .copy(allowedApps = selectedPackages.toList())
                            ConfigManager.saveConfig(this, cfg)
                        },
                        javaCrashLog     = javaCrash,
                        nativeCrashLog   = nativeCrash,
                        checkpointsLog   = checkpointsLog,
                        onClearCrashLogs = { CrashLogger.clearAll(this) },
                        onCopyToClipboard = { text -> copyToClipboard(text) }
                    )
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash_log", text))
        } catch (e: Exception) {
            Log.e("MainActivity", "Clipboard copy failed", e)
        }
    }

    private fun getInstalledAppsList(): List<AppItem> {
        val pm = packageManager
        return try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isEssential = listOf(
                        "chrome", "browser", "opera", "firefox",
                        "discord", "telegram", "youtube", "instagram", "tiktok"
                    ).any { app.packageName.contains(it) }
                    !isSystem || isEssential
                }
                .map { AppItem(it.loadLabel(pm).toString(), it.packageName) }
                .sortedBy { it.name }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading apps", e)
            emptyList()
        }
    }

    private fun requestVpnPermission() {
        // v3.7.5 CIS-MAX: чекпоинты для диагностики SIGKILL — сигнала,
        // который ни Kotlin UncaughtExceptionHandler, ни нативный
        // sigaction() перехватить не могут (агрессивные OEM-watchdog'и
        // вроде ColorOS/MIUI Autostart Manager убивают процесс напрямую).
        // Единственный способ понять, где именно умер процесс — частые
        // контрольные точки на диске, проверяемые при следующем запуске.
        CrashLogger.checkpoint(this, "MainActivity: 'Запустить VPN' нажата, requestVpnPermission()")
        val intent = VpnService.prepare(this)
        CrashLogger.checkpoint(this, "MainActivity: VpnService.prepare() вернул ${if (intent != null) "intent(нужно разрешение)" else "null(разрешение уже есть)"}")
        if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
        else onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
    }

    private fun stopVpnService() {
        startService(Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_STOP
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        CrashLogger.checkpoint(this, "MainActivity.onActivityResult: requestCode=$requestCode resultCode=$resultCode")
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            CrashLogger.checkpoint(this, "MainActivity: VPN-разрешение получено, сохраняем конфиг")
            val cfg = ConfigManager.loadPreset(selectedPresetId.value)
                .copy(allowedApps = selectedPackages.toList())
            ConfigManager.saveConfig(this, cfg)
            CrashLogger.checkpoint(this, "MainActivity: вызываем startService(ACTION_START)")
            startService(Intent(this, BypassVpnService::class.java).apply {
                action = BypassVpnService.ACTION_START
                putExtra(BypassVpnService.EXTRA_PRESET_ID, selectedPresetId.value)
                putStringArrayListExtra(
                    BypassVpnService.EXTRA_ALLOWED_APPS,
                    ArrayList(selectedPackages)
                )
            })
            CrashLogger.checkpoint(this, "MainActivity: startService(ACTION_START) вернулся без исключения")
        } else if (requestCode == VPN_REQUEST_CODE) {
            CrashLogger.checkpoint(this, "MainActivity: пользователь ОТКАЗАЛ в VPN-разрешении (resultCode=$resultCode)")
        }
    }
}

// ==============================================================================
// UI: Jetpack Compose — 3 вкладки: Пресеты / Приложения / Справка
// ==============================================================================

@Composable
fun MainScreen(
    installedApps: List<AppItem>,
    selectedPackages: MutableList<String>,
    selectedPresetId: MutableState<String>,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onSaveConfig: (String) -> Unit,
    javaCrashLog: String? = null,
    nativeCrashLog: String? = null,
    checkpointsLog: String? = null,
    onClearCrashLogs: () -> Unit = {},
    onCopyToClipboard: (String) -> Unit = {}
) {
    var isRunning by remember { mutableStateOf(false) }
    var tabIndex  by remember { mutableStateOf(0) }
    var crashLogsDismissed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "myVPNproject",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        // v3.7 CIS-MAX: реальный tun2socks (badvpn) + ciadpi (byedpi)
        Text(
            text = "DPI Bypass v3.7 CIS-MAX \u2022 native tun2socks + ciadpi",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // v3.7.3 CIS-MAX: если в прошлом запуске приложение упало (Kotlin
        // исключение ИЛИ нативный сигнал SIGABRT/SIGSEGV/...), показываем
        // причину прямо здесь — самое заметное место экрана, чтобы
        // пользователь мог скопировать текст и прислать разработчику без
        // необходимости в adb logcat. v3.7.5: если крашлоги пусты (обычно
        // это означает SIGKILL — сигнал, который вообще не перехватывается
        // ни Java, ни C кодом), всё равно показываем лог контрольных точек
        // Kotlin — по последней записанной строке видно последний живой шаг.
        val hasCrashLog = (!javaCrashLog.isNullOrBlank() || !nativeCrashLog.isNullOrBlank() || !checkpointsLog.isNullOrBlank())
        if (hasCrashLog && !crashLogsDismissed) {
            val combined = buildString {
                if (!javaCrashLog.isNullOrBlank()) append(javaCrashLog).append("\n")
                if (!nativeCrashLog.isNullOrBlank()) append(nativeCrashLog).append("\n")
                if (!checkpointsLog.isNullOrBlank()) {
                    append("=== KOTLIN CHECKPOINTS (последний = последний живой шаг) ===\n")
                    append(checkpointsLog).append("\n")
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "\u26a0\ufe0f Обнаружен лог предыдущего краша",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFFB71C1C)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // v3.7.5: показываем ПОСЛЕДНИЕ 4000 символов, а не
                        // первые — при накоплении многих чекпоинтов самое
                        // важное (последний живой шаг перед крахом) всегда
                        // в конце текста. Кнопка "Копировать" всё равно
                        // копирует полный текст без обрезки.
                        combined.takeLast(4000),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = Color(0xFF4E342E),
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = { onCopyToClipboard(combined) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Копировать", fontSize = 12.sp) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                onClearCrashLogs()
                                crashLogsDismissed = true
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Закрыть", fontSize = 12.sp) }
                    }
                }
            }
        }

        Button(
            onClick = {
                if (isRunning) { onStopVpn(); isRunning = false }
                else           { onStartVpn(); isRunning = true  }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFE91E63) else Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = if (isRunning) "\u23f9 ОСТАНОВИТЬ VPN" else "\u25b6 ЗАПУСТИТЬ VPN",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Text(
            text = if (isRunning) "\u25cf Активен | Пресет: ${selectedPresetId.value}"
                   else            "\u25cb Остановлен",
            fontSize = 13.sp,
            color = if (isRunning) Color(0xFF4CAF50) else Color.Gray,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }) {
                Text("Пресеты", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
            }
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }) {
                Text(
                    "Приложения (${selectedPackages.size})",
                    modifier = Modifier.padding(10.dp),
                    fontWeight = FontWeight.Bold
                )
            }
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }) {
                Text("Справка", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (tabIndex) {
            0 -> PresetsTab(selectedPresetId, onSaveConfig)
            1 -> AppsTab(installedApps, selectedPackages)
            2 -> HelpTab()
        }
    }
}

@Composable
fun PresetsTab(
    selectedPresetId: MutableState<String>,
    onSaveConfig: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(PRESETS) { preset ->
            val isSelected = selectedPresetId.value == preset.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        selectedPresetId.value = preset.id
                        onSaveConfig(preset.id)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(if (isSelected) 4.dp else 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = preset.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "\u2713",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(text = preset.description, fontSize = 12.sp, color = Color.Gray)
                    val cliArgs = ConfigManager.toCliArgs(preset.config).joinToString(" ")
                    if (cliArgs.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = cliArgs,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppsTab(
    installedApps: List<AppItem>,
    selectedPackages: MutableList<String>
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(installedApps) { app ->
            val isChecked = selectedPackages.contains(app.packageName)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isChecked) selectedPackages.remove(app.packageName)
                        else           selectedPackages.add(app.packageName)
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        if (checked) selectedPackages.add(app.packageName)
                        else         selectedPackages.remove(app.packageName)
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(app.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(app.packageName, fontSize = 11.sp, color = Color.Gray)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun HelpTab() {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {
        // Карточка 1: Инструкция для пользователя
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Как пользоваться",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. Выберите пресет на вкладке \u2018Пресеты\u2019.\n" +
                    "   \u2022 Универсальный \u2014 для большинства случаев,\n" +
                    "     автоматически определяет оператора по ASN.\n" +
                    "   \u2022 YouTube \u2014 если YouTube заблокирован.\n" +
                    "   \u2022 Telegram \u2014 для обхода блокировок Telegram.\n" +
                    "   \u2022 Минимальный \u2014 экономия батареи.\n" +
                    "   \u2022 Максимальный \u2014 если остальные не помогли.\n" +
                    "   \u2022 KZ/МТС/Билайн/Ростелеком \u2014 точный тюнинг\n" +
                    "     под конкретного оператора СНГ.\n\n" +
                    "2. На вкладке \u2018Приложения\u2019 отметьте браузеры\n" +
                    "   и приложения, трафик которых нужно обходить.\n\n" +
                    "3. Нажмите \u2018ЗАПУСТИТЬ VPN\u2019.\n\n" +
                    "Как работает:\n" +
                    "Трафик перехватывается локально на устройстве\n" +
                    "(не уходит на внешний сервер). TLS ClientHello\n" +
                    "разбивается на фрагменты (IPv4 и IPv6) \u2014 DPI\n" +
                    "провайдера не распознаёт сигнатуру блокируемого\n" +
                    "ресурса. Обычный DNS (UDP:53) перехватывается и\n" +
                    "резолвится через DoH \u2014 провайдер не видит,\n" +
                    "какие домены вы посещаете.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Исправление #3: актуальная карточка разработчика без ссылок на удалённые JNI-файлы
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Архитектура v3.7 CIS-MAX",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "TUN \u2192 tun2socks (badvpn, native)\n" +
                    "    \u2192 SOCKS5 127.0.0.1:1080\n" +
                    "    \u2192 ciadpi (byedpi, native, TCP split/fake)\n" +
                    "    \u2192 интернет\n\n" +
                    "Движок:\n" +
                    "  \u2022 Полноценный TCP/IP стек (lwIP) \u2014 не только\n" +
                    "    фрагментация TLS ClientHello, а весь TCP\n" +
                    "  \u2022 UDP (QUIC/DNS) через SOCKS5 UDP ASSOCIATE\n" +
                    "  \u2022 Перехват UDP:53 \u2192 резолв через DoH\n" +
                    "  \u2022 Авто-определение оператора по ASN\n" +
                    "  \u2022 Пресеты СНГ: kz-telecom/mts-ru/beeline-ru/\n" +
                    "    rostelecom\n\n" +
                    "В разработке:\n" +
                    "  \u2022 ECH (Encrypted Client Hello, TLS 1.3)\n" +
                    "Детали: github.com/MaksKbz/myVPNproject",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
