package com.makskbz.myvpnproject

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VpnControlScreen(
                        onStartVpn = { startVpnEngine() },
                        onStopVpn = { stopVpnEngine() }
                    )
                }
            }
        }
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
            }
            startService(intent)
        }
    }
}

@Composable
fun VpnControlScreen(onStartVpn: () -> Unit, onStopVpn: () -> Unit) {
    var isRunning by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "myVPNproject",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "B4-inspired DPI Bypass VPN Client (v1.02)",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
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
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFE91E63) else Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = if (isRunning) "STOP VPN" else "START VPN",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Секция с инструкцией на русском языке
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ИНСТРУКЦИЯ ПО ИСПОЛЬЗОВАНИЮ v1.02",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Данное приложение использует локальный интерфейс VPN (VpnService) Android без отправки трафика на внешние сервера (без-серверный обход DPI). Модификация пакетов происходит локально на вашем процессоре.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Как это работает:\n" +
                            "1. Нажмите зеленую кнопку 'START VPN'.\n" +
                            "2. Разрешите системе Android создать VPN-подключение.\n" +
                            "3. Приложение начнет перехватывать ваши исходящие запросы:\n" +
                            "   • Трафик QUIC (UDP 443) блокируется. Это вынуждает приложения и сайты (например, YouTube) переключаться на TCP-протокол.\n" +
                            "   • Пакеты TLS ClientHello фрагментируются. DPI-система провайдера видит разрозненные сегменты и не может распознать заблокированный домен (SNI).\n" +
                            "   • Применяется защищенный сокет-транзит через защитные методы Android OS для исключения мертвых циклов и падения интернета.\n" +
                            "   • Весь остальной сетевой трафик беспрепятственно передается в интернет.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Внимание: Для корректной работы интернет-доступа убедитесь, что в системе не включены другие VPN-сервисы, а DNS-серверы вашего провайдера не блокируют запрашиваемые домены на уровне IP.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
