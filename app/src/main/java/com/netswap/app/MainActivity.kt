package com.netswap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Cream = Color(0xFFF8F6F0)
private val Ink = Color(0xFF252A32)
private val Muted = Color(0xFF7C8490)
private val Lavender = Color(0xFFE8E2FA)
private val Mint = Color(0xFFDDF2E8)
private val White = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                background = Cream,
                surface = White,
                primary = Color(0xFF8274B8),
                onBackground = Ink,
                onSurface = Ink
            )) {
                NetSwapHome()
            }
        }
    }
}

@Composable
private fun NetSwapHome() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Cream
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("NetSwap", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Share a little. Stay connected.", fontSize = 13.sp, color = Muted)
                }
                Box(
                    modifier = Modifier
                        .background(Lavender, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text("BETA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF65568F))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(White, RoundedCornerShape(26.dp))
                    .padding(20.dp)
            ) {
                Text("Your connection", fontSize = 14.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Text("Ready when you are", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your NetSwap account and sharing controls will appear here.",
                    fontSize = 13.sp,
                    color = Muted,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Mint, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Text("Internet sharing is not active yet", fontSize = 13.sp, color = Color(0xFF3E725B), fontWeight = FontWeight.Medium)
                }
            }

            Text("Quick actions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile("Send data", "Share with a friend", Lavender, Modifier.weight(1f))
                ActionTile("Receive", "View requests", Mint, Modifier.weight(1f))
            }

            Spacer(Modifier.weight(1f))
            Text(
                "NetSwap • Private by design",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 12.sp,
                color = Muted
            )
        }
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(White, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .background(tint, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text(if (title == "Send data") "↗" else "↓", fontSize = 19.sp, color = Ink, fontWeight = FontWeight.Bold)
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(subtitle, fontSize = 12.sp, color = Muted)
    }
}
