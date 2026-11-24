package com.wakepilot.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakepilot.app.data.ConfigStore
import com.wakepilot.app.net.DiscoveredHost
import com.wakepilot.app.net.LanScanner
import com.wakepilot.app.service.SentinelForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SentinelScreen(store: ConfigStore, onSwitchRole: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime by SentinelForegroundService.state.collectAsState()

    var cfg by remember { mutableStateOf(store.getOrInitSentinel()) }
    var relayUrl by remember { mutableStateOf(cfg.relayUrl) }
    var pcName by remember { mutableStateOf(cfg.pcName) }
    var pcMac by remember { mutableStateOf(cfg.pcMac) }
    var pcIp by remember { mutableStateOf(cfg.pcIp) }
    var bcastIp by remember { mutableStateOf(cfg.broadcastIp) }
    var rustDeskId by remember { mutableStateOf(cfg.rustDeskId) }
    var scanning by remember { mutableStateOf(false) }
    var hosts by remember { mutableStateOf<List<DiscoveredHost>>(emptyList()) }
    var bedsideClock by remember { mutableStateOf(false) }

    val pairingUri = remember(cfg) { store.buildPairingUri(cfg) }
    val qrBitmap = remember(pairingUri) { QrUtils.generateQrBitmap(pairingUri, 480) }

    // OLED-safe pure-black bedside clock mode
    if (bedsideClock) {
        var nowText by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (true) {
                nowText = fmt.format(Date())
                delay(1000)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { bedsideClock = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(nowText, fontSize = 76.sp, fontWeight = FontWeight.Light, color = Color(0xFF475569))
                Spacer(Modifier.height(10.dp))
                Text(
                    if (runtime.relayConnected) "● 家中哨兵在线 (E2EE)" else "○ ${runtime.statusText}",
                    fontSize = 14.sp,
                    color = if (runtime.relayConnected) Color(0xFF15803D) else Color(0xFFB45309),
                )
                Spacer(Modifier.height(24.dp))
                Text("轻触任意处退出床头时钟", fontSize = 12.sp, color = Color(0xFF334155))
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("家中哨兵模式", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    if (runtime.relayConnected) "● 盲中继在线 (E2EE)" else "○ ${runtime.statusText}",
                    fontSize = 12.sp,
                    color = if (runtime.relayConnected) StatusGreen else StatusAmber,
                )
            }
            TextButton(onClick = onSwitchRole) { Text("切换角色", color = AccentBlue) }
        }

        Spacer(Modifier.height(12.dp))

        // Service Control + Battery Whitelist + Bedside Clock
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = BorderStroke(1.dp, BorderDark),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("常驻后台哨兵服务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Switch(
                        checked = runtime.running,
                        onCheckedChange = { enable ->
                            val updated = cfg.copy(
                                relayUrl = relayUrl,
                                pcName = pcName,
                                pcMac = pcMac,
                                pcIp = pcIp,
                                broadcastIp = bcastIp,
                                rustDeskId = rustDeskId,
                                serviceEnabled = enable,
                            )
                            store.saveSentinel(updated)
                            cfg = updated
                            if (enable) SentinelForegroundService.start(ctx) else SentinelForegroundService.stop(ctx)
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { requestBatteryWhitelist(ctx) },
                    ) { Text("防杀后台白名单", fontSize = 12.sp) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { bedsideClock = true },
                    ) { Text("暗黑床头时钟", fontSize = 12.sp) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // LAN Auto-Scanner Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = BorderStroke(1.dp, BorderDark),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("1. 一键扫描并绑定目标 Windows 电脑", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !scanning,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    onClick = {
                        scanning = true
                        scope.launch {
                            val list = withContext(Dispatchers.IO) { LanScanner.scanLan() }
                            hosts = list
                            scanning = false
                            if (list.isEmpty()) {
                                Toast.makeText(ctx, "未扫描到设备：请确认电脑已运行 wakepilot-agent.exe 且与备用机在同一路由器", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) {
                    Text(if (scanning) "正在广播扫描局域网..." else "扫描局域网电脑（免手抄 MAC）", color = Color(0xFF090D16), fontWeight = FontWeight.Bold)
                }

                hosts.forEach { h ->
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pcName = h.pcName
                                pcMac = h.mac
                                pcIp = h.ip
                                bcastIp = h.broadcastIp
                                if (h.rustDeskId.isNotBlank()) rustDeskId = h.rustDeskId
                                val updated = cfg.copy(
                                    relayUrl = relayUrl,
                                    pcName = pcName,
                                    pcMac = pcMac,
                                    pcIp = pcIp,
                                    broadcastIp = bcastIp,
                                    rustDeskId = rustDeskId,
                                )
                                store.saveSentinel(updated)
                                cfg = updated
                                Toast.makeText(ctx, "已自动填入 ${h.pcName} (${h.mac})", Toast.LENGTH_SHORT).show()
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${h.pcName}  [${h.source}]", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                            Text("MAC: ${h.mac} | IP: ${h.ip}", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = relayUrl, onValueChange = { relayUrl = it }, label = { Text("云端盲中继地址 (如 http://YOUR_SERVER_IP:8443)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = pcName, onValueChange = { pcName = it }, label = { Text("电脑名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = pcMac, onValueChange = { pcMac = it }, label = { Text("电脑有线 MAC 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pcIp, onValueChange = { pcIp = it }, label = { Text("电脑内网 IP") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = bcastIp, onValueChange = { bcastIp = it }, label = { Text("子网广播 IP") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = rustDeskId, onValueChange = { rustDeskId = it }, label = { Text("RustDesk ID / 直连 IP（可选，用于远程输密码）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val updated = cfg.copy(
                            relayUrl = relayUrl,
                            pcName = pcName,
                            pcMac = pcMac,
                            pcIp = pcIp,
                            broadcastIp = bcastIp,
                            rustDeskId = rustDeskId,
                        )
                        store.saveSentinel(updated)
                        cfg = updated
                        if (runtime.running) {
                            SentinelForegroundService.stop(ctx)
                            SentinelForegroundService.start(ctx)
                        }
                        Toast.makeText(ctx, "哨兵配置已保存并更新配对二维码", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("保存配置并刷新配对码") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // QR Code E2EE Pairing Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = BorderStroke(1.dp, BorderDark),
        ) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("2. 主力手机扫码绑定（E2EE 密钥直传）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(10.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Pairing QR",
                        modifier = Modifier
                            .size(210.dp)
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("哨兵 ID: ${cfg.sentinelId}", fontSize = 12.sp, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("WakePilot Pair", pairingUri))
                            Toast.makeText(ctx, "已复制配对链接", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("复制配对链接", fontSize = 12.sp) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            cfg = store.rotateSentinelKeys()
                            Toast.makeText(ctx, "已轮换新的 E2EE 密钥，请主力机重新扫码", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("重置密钥", fontSize = 12.sp, color = StatusAmber) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Runtime Logs
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
            border = BorderStroke(1.dp, BorderDark),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("实时哨兵日志", fontSize = 12.sp, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (runtime.logs.isEmpty()) "等待启动..." else runtime.logs.takeLast(12).joinToString("\n"),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

private fun requestBatteryWhitelist(ctx: Context) {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        Toast.makeText(ctx, "已处于电池优化白名单中 ✓", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }
        ctx.startActivity(intent)
    }.onFailure {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
