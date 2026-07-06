package com.makskbz.myvpnproject

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
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
import com.makskbz.myvpnproject.vpn.PRESETS
import com.makskbz.myvpnproject.vpn.Preset

data class AppItem(val name: String, val packageName: String)

class MainActivity : ComponentActivity() {

    private val VPN_REQUEST_CODE = 1001
    private var selectedPackages = mutableStateListOf<String>()
    private var selectedPresetId = mutableStateOf("universal")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Восстанавливаем сохранённую конфигурацию
        val savedConfig = ConfigManager.loadConfig(this)
        selectedPresetId.value = savedConfig.presetName
        selectedPackages.addAll(savedConfig.allowedApps)

        val installedApps = getInstalledAppsList()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        installedApps = installedApps,
                        selectedPackages = selectedPackages,
                        selectedPresetId = selectedPresetId,
                        onStartVpn  = { requestVpnPermission() },
                        onStopVpn   = { stopVpnService() },
                        onSaveConfig = { presetId ->
                            selectedPresetId.value = presetId
                            val cfg = ConfigManager.loadPreset(presetId)
                                .copy(allowedApps = selectedPackages.toList())
                            ConfigManager.saveConfig(this, cfg)
                        }
                    )
                }
            }
        }
    }

    private fun getInstalledAppsList(): List<AppItem> {
        val apps = mutableListOf<AppItem>()
        val pm = packageManager
        return try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isEssential = listOf("chrome", "browser", "opera", "firefox",
                    "discord", "telegram", "youtube", "instagram", "tiktok")
                    .any { app.packageName.contains(it) }
                if (!isSystem || isEssential) {
                    apps.add(AppItem(app.loadLabel(pm).toString(), app.packageName))
                }
            }
            apps.sortedBy { it.name }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading apps", e)
            apps
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
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
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            // Сохраняем конфиг перед запуском
            val cfg = ConfigManager.loadPreset(selectedPresetId.value)
                .copy(allowedApps = selectedPackages.toList())
            ConfigManager.saveConfig(this, cfg)

            startService(Intent(this, BypassVpnService::class.java).apply {
                action = BypassVpnService.ACTION_START
                putExtra(BypassVpnService.EXTRA_PRESET_ID, selectedPresetId.value)
                putStringArrayListExtra(
                    BypassVpnService.EXTRA_ALLOWED_APPS,
                    ArrayList(selectedPackages)
                )
            })
        }
    }
}

// ==============================================================================
// UI: Jetpack Compose — 3 вкладки: Пресеты / Приложения / Инструкция
// ==============================================================================

@Composable
fun MainScreen(
    installedApps: List<AppItem>,
    selectedPackages: MutableList<String>,
    selectedPresetId: MutableState<String>,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onSaveConfig: (String) -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }
    var tabIndex  by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Заголовок ---
        Text(
            text = "myVPNproject",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "DPI Bypass v3.0 • ciadpi engine",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // --- Кнопка Старт/Стоп ---
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
                text = if (isRunning) "⏹ ОСТАНОВИТЬ VPN" else "▶ ЗАПУСТИТЬ VPN",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Статус
        Text(
            text = if (isRunning) "● Активен | Пресет: ${selectedPresetId.value}"
                   else            "○ Остановлен",
            fontSize = 13.sp,
            color = if (isRunning) Color(0xFF4CAF50) else Color.Gray,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // --- Вкладки ---
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }) {
                Text("Пресеты", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
            }
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }) {
                Text("Приложения (${selectedPackages.size})",
                    modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
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
                            Text("✓", color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preset.description,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    // CLI-параметры
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
                    "1. Выберите пресет на вкладке 'Пресеты'.\n" +
                    "   • Универсальный — для большинства случаев.\n" +
                    "   • YouTube — если YouTube заблокирован.\n" +
                    "   • Telegram — для обхода блокировок Telegram.\n" +
                    "   • Минимальный — экономия батареи.\n" +
                    "   • Максимальный — если остальные не помогли.\n\n" +
                    "2. На вкладке 'Приложения' отметьте браузеры\n" +
                    "   и приложения, трафик которых нужно обходить.\n\n" +
                    "3. Нажмите 'ЗАПУСТИТЬ VPN'.\n\n" +
                    "Как работает:\n" +
                    "Трафик перехватывается локально на устройстве\n" +
                    "(не уходит на внешний сервер). Пакеты изменяются\n" +
                    "так, чтобы DPI провайдера не распознал сигнатуру\n" +
                    "блокируемого ресурса.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Для разработчиков",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Для активации нативного движка добавьте submodule:\n\n" +
                    "git submodule add\n" +
                    "  https://github.com/hufrea/byedpi.git\n" +
                    "  app/src/main/jni/ciadpi\n\n" +
                    "git submodule add\n" +
                    "  https://github.com/ambrop72/badvpn.git\n" +
                    "  app/src/main/jni/tun2socks\n\n" +
                    "Затем раскомментируйте #include в:\n" +
                    "  ciadpi_jni.c\n" +
                    "  tun2socks_jni.c\n\n" +
                    "Подробнее: см. README.md",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
