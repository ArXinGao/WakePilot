package com.wakepilot.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakepilot.app.data.AppRole

@Composable
fun HomeScreen(onSelectRole: (AppRole) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("WakePilot", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "零 VPN 冲突 · E2EE 端到端加密 · 远程开机与 AI 桌面唤醒",
            fontSize = 13.sp,
            color = TextMuted,
        )

        Spacer(Modifier.height(32.dp))

        RoleCard(
            title = "设为随身遥控器（主力手机）",
            subtitle = "扫描备用机二维码绑定，4G/5G 一键开机、查看启动进度、桌面小组件与下拉快捷磁贴，不占安卓 VPN 槽位。",
            borderColor = AccentBlue,
            onClick = { onSelectRole(AppRole.CONTROLLER) },
        )

        Spacer(Modifier.height(16.dp))

        RoleCard(
            title = "设为家中哨兵（闲置备用手机）",
            subtitle = "连家用 Wi-Fi 插电常驻，一键扫描发现电脑 MAC，主动建立 443 加密长连接，支持暗黑床头时钟模式。",
            borderColor = AccentPurple,
            onClick = { onSelectRole(AppRole.SENTINEL) },
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "提示：同一份 APK 安装在两台手机上，分别选择对应角色即可（随时可切换）。",
            fontSize = 12.sp,
            color = TextMuted,
        )
    }
}

@Composable
private fun RoleCard(
    title: String,
    subtitle: String,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = BorderStroke(1.2.dp, borderColor.copy(alpha = 0.65f)),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, fontSize = 13.sp, color = TextMuted, lineHeight = 19.sp)
        }
    }
}
