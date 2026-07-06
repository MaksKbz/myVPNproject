package com.makskbz.myvpnproject

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "myVPNproject",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "B4-inspired DPI Bypass VPN Client",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 48.dp)
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

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Active strategies:", fontWeight = FontWeight.Bold)
                Text(text = "• TLS ClientHello Split (Index: 2)", modifier = Modifier.padding(top = 4.dp))
                Text(text = "• Drop QUIC (UDP port 443)", modifier = Modifier.padding(top = 2.dp))
                Text(text = "• DNS redirection to DoH", modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
