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

class MainActivity : ComponentActivity() {
    
    private val VPN_REQUEST_CODE = 1001
    private var selectedPackages = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Получаем полный список установленных приложений с поддержкой QUERY_ALL_PACKAGES
        val installedApps = getInstalledAppsList()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VpnControlScreen(
                        installedApps = installedApps,
                        selectedPackages = selectedPackages,
                        onStartVpn = { startVpnEngine() },
                        onStopVpn = { stopVpnEngine() }
                    )
                }
            }
        }
    }

    private fun getInstalledAppsList(): List<AppItem> {
        val apps = mutableListOf<AppItem>()
        val pm = packageManager
        
        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            Log.d("MainActivity", "Found ${packages.size} packages installed")
            
            for (app in packages) {
                val name = app.loadLabel(pm).toString()
                val packageName = app.packageName
                
                // Пропускаем системные фоновые службы, оставляя пользовательские программы и браузеры
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isEssentialApp = packageName == "com.android.chrome" || 
                                     packageName == "com.google.android.youtube" || 
                                     packageName.contains("browser") || 
                                     packageName.contains("opera") || 
                                     packageName.contains("discord") || 
                                     packageName.contains("telegram")

                if (!isSystemApp || isEssentialApp) {
                    apps.add(AppItem(name, packageName))
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error retrieving installed applications", e)
        }

        return apps.sortedBy { it.name }
    }

    private fun startVpnEngine() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
        }
    }

    private fun stopVpnEngine() {
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            val intent = Intent(this, BypassVpnService::class.java).apply {
                action = BypassVpnService.ACTION_START
                putStringArrayListExtra(BypassVpnService.EXTRA_ALLOWED_APPS, ArrayList(selectedPackages))
            }
            startService(intent)
        }
    }
}

data class AppItem(val name: String, val packageName: String)

@Composable
fun VpnControlScreen(
    installedApps: List<AppItem>,
    selectedPackages: MutableList<String>,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }
    var tabIndex by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "myVPNproject",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "DPI Bypass VPN Client (v1.08)",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
        )

        Button(
            onClick = {
                if (isRunning) {
                    onStopVpn()
                    isRunning = false
                } else {
                    onStartVpn()
                    isRunning = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFE91E63) else Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = if (isRunning) "ОСТАНОВИТЬ VPN" else "ЗАПУСТИТЬ VPN",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Переключатель вкладок: Управление / Выбор приложений
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }) {
                Text(text = "Инструкция", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
            }
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }) {
                Text(text = "Приложения (${selectedPackages.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (tabIndex == 0) {
            // Вкладка 1: Инструкция
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ИНСТРУКЦИЯ ПО ТЕСТИРОВАНИЮ v1.08",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Для проверки обхода блокировок DPI (например, YouTube или заблокированных сайтов в Казахстане):\n" +
                                    "1. Перейдите на вкладку 'Приложения'.\n" +
                                    "2. Отметьте галочками нужный браузер (например, Opera, Chrome или Яндекс.Браузер).\n" +
                                    "3. Вернитесь на эту вкладку и нажмите зеленую кнопку 'ЗАПУСТИТЬ VPN'.\n" +
                                    "4. Откройте выбранное приложение — весь его веб-трафик теперь автоматически фрагментируется локально на вашем процессоре, обходя DPI провайдера.\n" +
                                    "5. Все остальные (не отмеченные) приложения будут ходить в интернет напрямую без VPN-прослойки, сохраняя максимальную скорость.\n\n" +
                                    "ВАЖНОЕ ЗАМЕЧАНИЕ (Конфликт пакетов при обновлении):\n" +
                                    "Если при установке файла v1.08 пишется 'Ошибка конфликта пакета', обязательно удалите предыдущую установленную версию приложения вручную с телефона один раз. После этого новые версии с GitHub Actions будут беспрепятственно устанавливаться поверх без удаления благодаря унифицированной подписи сборщика!",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        } else {
            // Вкладка 2: Выбор приложений для туннелирования
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(installedApps) { app ->
                    val isChecked = selectedPackages.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isChecked) {
                                    selectedPackages.remove(app.packageName)
                                } else {
                                    selectedPackages.add(app.packageName)
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (checked == true) {
                                    selectedPackages.add(app.packageName)
                                } else {
                                    selectedPackages.remove(app.packageName)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = app.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(text = app.packageName, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    Divider()
                }
            }
        }
    }
}
