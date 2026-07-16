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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makskbz.myvpnproject.vpn.BypassVpnService
import com.makskbz.myvpnproject.vpn.ConfigManager
import com.makskbz.myvpnproject.vpn.CrashLogger
import com.makskbz.myvpnproject.vpn.ProxyEngine

data class AppItem(val name: String, val packageName: String)

// v3.8 CIS-MAX: пользователи не разбираются в терминах DPI-обхода (split/
// disorder/OOB/fake TTL) — ручной выбор пресета убран из UI полностью.
// Система ВСЕГДА стартует с самого лёгкого универсального метода и сама
// последовательно перебирает более агрессивные варианты при неудачных
// проверках связности (см. ConfigManager.AUTO_SWITCH_ORDER и
// BypassVpnService.startPresetMonitor()/switchToNextPreset()). Название
// каждого метода и что именно он делает описано СПРАВОЧНО во вкладке
// "Справка", чтобы не перегружать рабочий экран техническими деталями.
private const val DEFAULT_PRESET_ID = "universal"

class MainActivity : ComponentActivity() {

    private val VPN_REQUEST_CODE = 1001
    private val NOTIF_PERMISSION_CODE = 2001
    private var selectedPackages = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v3.7.3 CIS-MAX: устанавливаем сборщик крашей (Kotlin + нативный
        // SIGABRT/SIGSEGV/...) КАК МОЖНО РАНЬШЕ — до любых других вызовов,
        // чтобы даже краш при самом первом нажатии "Запустить VPN" был
        // перехвачен и сохранён на диск. v3.8: карточка с логом убрана с
        // главного экрана (не пугает обычного пользователя техническими
        // деталями) — теперь доступна только внутри вкладки "Справка" по
        // явному нажатию "Показать диагностический лог".
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
        selectedPackages.addAll(savedConfig.allowedApps)

        val installedApps = getInstalledAppsList()

        // Читаем логи прошлого краша (если приложение вылетело в предыдущем
        // запуске) — v3.8: больше НЕ показываем автоматически на главном
        // экране, только передаём во вкладку "Справка" для просмотра по
        // требованию.
        val javaCrash = CrashLogger.readJavaCrashLog(this)
        val nativeCrash = CrashLogger.readNativeCrashLog(this)
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
                        onStartVpn       = { requestVpnPermission() },
                        onStopVpn        = { stopVpnService() },
                        onSaveApps       = {
                            val cfg = ConfigManager.loadPreset(DEFAULT_PRESET_ID)
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
                        "discord", "telegram", "youtube", "instagram", "tiktok",
                        "whatsapp"
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
            // v3.8 CIS-MAX: пресет больше не выбирается пользователем — всегда
            // стартуем с "universal" (самый лёгкий, универсальный метод),
            // дальше BypassVpnService сам переключается на более агрессивные
            // пресеты при необходимости (см. ConfigManager.AUTO_SWITCH_ORDER).
            val cfg = ConfigManager.loadPreset(DEFAULT_PRESET_ID)
                .copy(allowedApps = selectedPackages.toList())
            ConfigManager.saveConfig(this, cfg)
            CrashLogger.checkpoint(this, "MainActivity: вызываем startService(ACTION_START)")
            startService(Intent(this, BypassVpnService::class.java).apply {
                action = BypassVpnService.ACTION_START
                putExtra(BypassVpnService.EXTRA_PRESET_ID, DEFAULT_PRESET_ID)
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
// UI: Jetpack Compose — v3.8 CIS-MAX
// 2 вкладки: Приложения / Справка (вкладка "Пресеты" убрана — выбор метода
// обхода теперь полностью автоматический, см. комментарий у DEFAULT_PRESET_ID)
// ==============================================================================

@Composable
fun MainScreen(
    installedApps: List<AppItem>,
    selectedPackages: MutableList<String>,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onSaveApps: () -> Unit,
    javaCrashLog: String? = null,
    nativeCrashLog: String? = null,
    checkpointsLog: String? = null,
    onClearCrashLogs: () -> Unit = {},
    onCopyToClipboard: (String) -> Unit = {}
) {
    var isRunning by remember { mutableStateOf(false) }
    var tabIndex  by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // v3.8 CIS-MAX: переименовано в "VPN_KBZMAX" по требованию.
        Text(
            text = "VPN_KBZMAX",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Обход блокировок \u2022 автоматический подбор метода",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Button(
            onClick = {
                if (isRunning) { onStopVpn(); isRunning = false }
                else           { onSaveApps(); onStartVpn(); isRunning = true }
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
            text = if (isRunning) "\u25cf Активен \u2014 метод обхода подбирается автоматически"
                   else            "\u25cb Остановлен",
            fontSize = 13.sp,
            color = if (isRunning) Color(0xFF4CAF50) else Color.Gray,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }) {
                Text(
                    "Приложения (${selectedPackages.size})",
                    modifier = Modifier.padding(10.dp),
                    fontWeight = FontWeight.Bold
                )
            }
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }) {
                Text("Справка", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (tabIndex) {
            0 -> AppsTab(installedApps, selectedPackages)
            1 -> HelpTab(
                javaCrashLog = javaCrashLog,
                nativeCrashLog = nativeCrashLog,
                checkpointsLog = checkpointsLog,
                onClearCrashLogs = onClearCrashLogs,
                onCopyToClipboard = onCopyToClipboard
            )
        }
    }
}

@Composable
fun AppsTab(
    installedApps: List<AppItem>,
    selectedPackages: MutableList<String>
) {
    // v3.8 CIS-MAX: строка поиска — список установленных приложений может
    // быть длинным (десятки-сотни пакетов), пользователю неудобно listать
    // вручную в поисках нужного приложения, чтобы поставить галочку.
    var query by remember { mutableStateOf("") }
    val filteredApps = remember(query, installedApps) {
        if (query.isBlank()) installedApps
        else installedApps.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            placeholder = { Text("Поиск приложения\u2026") },
            singleLine = true,
            leadingIcon = { Text("\uD83D\uDD0D", fontSize = 16.sp) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Text("\u2715", fontSize = 14.sp)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Ничего не найдено",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredApps, key = { it.packageName }) { app ->
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
    }
}

@Composable
fun HelpTab(
    javaCrashLog: String? = null,
    nativeCrashLog: String? = null,
    checkpointsLog: String? = null,
    onClearCrashLogs: () -> Unit = {},
    onCopyToClipboard: (String) -> Unit = {}
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {
        // v3.8.1 CIS-MAX: справка оставлена ТОЛЬКО с практической
        // пользовательской информацией — как пользоваться приложением.
        // Технические детали (какие методы обхода используются, как
        // устроен движок изнутри, ссылка на репозиторий разработки)
        // убраны по требованию — эта информация не помогает обычному
        // пользователю и только загромождает экран.
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
                    "1. На вкладке \u2018Приложения\u2019 отметьте (или найдите\n" +
                    "   через поиск) браузеры и приложения, трафик\n" +
                    "   которых нужно обходить.\n\n" +
                    "2. Нажмите \u2018ЗАПУСТИТЬ VPN\u2019.\n\n" +
                    "Больше ничего настраивать не нужно \u2014 приложение\n" +
                    "само подбирает подходящий способ обхода блокировки\n" +
                    "и переключается на более сильный, если сайт или\n" +
                    "сервис остаётся недоступен.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // v3.8 CIS-MAX: диагностический лог крашей — раньше показывался
        // автоматически прямо на главном экране (пугал пользователя видом
        // технических деталей при каждом запуске после сбоя), теперь скрыт
        // здесь, в справке, и открывается только по явному нажатию.
        DiagnosticLogCard(
            javaCrashLog = javaCrashLog,
            nativeCrashLog = nativeCrashLog,
            checkpointsLog = checkpointsLog,
            onClearCrashLogs = onClearCrashLogs,

            onCopyToClipboard = onCopyToClipboard
        )
    }
}

@Composable
private fun DiagnosticLogCard(
    javaCrashLog: String?,
    nativeCrashLog: String?,
    checkpointsLog: String?,
    onClearCrashLogs: () -> Unit,
    onCopyToClipboard: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }

    val hasLog = !cleared && (!javaCrashLog.isNullOrBlank() || !nativeCrashLog.isNullOrBlank() || !checkpointsLog.isNullOrBlank())
    val combined = remember(javaCrashLog, nativeCrashLog, checkpointsLog) {
        buildString {
            if (!javaCrashLog.isNullOrBlank()) append(javaCrashLog).append("\n")
            if (!nativeCrashLog.isNullOrBlank()) append(nativeCrashLog).append("\n")
            if (!checkpointsLog.isNullOrBlank()) {
                append("=== KOTLIN CHECKPOINTS (последний = последний живой шаг) ===\n")
                append(checkpointsLog).append("\n")
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Диагностический лог",
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hasLog)
                    "Обнаружен технический лог (для разработчика, при\n" +
                    "обращении в поддержку). Обычно эти данные не\n" +
                    "нужны для повседневного использования."
                else
                    "Логов нет \u2014 сбоев с прошлого запуска не\n" +
                    "обнаружено.",
                fontSize = 12.sp,
                color = Color.Gray
            )

            if (hasLog) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (expanded) "Скрыть лог" else "Показать диагностический лог", fontSize = 12.sp)
                }

                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // Показываем последние 8000 символов — самое важное
                        // (последний живой шаг перед крахом) всегда в конце.
                        // Кнопка "Копировать" копирует полный текст без обрезки.
                        combined.takeLast(8000),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
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
                                cleared = true
                                expanded = false
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Очистить", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
