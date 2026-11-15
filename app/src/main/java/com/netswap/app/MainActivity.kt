package com.netswap.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Cream = Color(0xFFF8F6F0)
private val Ink = Color(0xFF252A32)
private val Muted = Color(0xFF7C8490)
private val Lavender = Color(0xFFE8E2FA)
private val Mint = Color(0xFFDDF2E8)
private val White = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestVpnSetup()
    }

    private fun startVpnWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnSetup()
        }
    }

    private fun launchVpnService(action: String) {
        val intent = Intent(this, NetSwapVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun startVpnService() {
        launchVpnService(NetSwapVpnService.ACTION_START)
        prefs.edit().putBoolean("vpn_active", true).apply()
    }

    private fun disconnectVpnService() {
        launchVpnService(NetSwapVpnService.ACTION_STOP)
        prefs.edit().putBoolean("vpn_active", false).apply()
    }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
    }

    private fun requestVpnSetup() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
        else startVpnService()
    }

    private val prefs by lazy { getSharedPreferences("netswap_session", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = runCatching {
            val refresh = prefs.getString("refresh_token", null) ?: return@runCatching null
            val token = prefs.getString("access_token", null) ?: ""
            val id = prefs.getString("user_id", null) ?: return@runCatching null
            AuthSession(token, refresh, id, prefs.getString("email", "") ?: "")
        }.getOrNull()

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Cream, surface = White, primary = Color(0xFF8274B8), onBackground = Ink, onSurface = Ink)) {
                var session by remember { mutableStateOf(initial) }
                var uid by remember { mutableStateOf("") }
                var requests by remember { mutableStateOf(emptyList<ShareRequest>()) }
                var loading by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                var vpnActive by remember { mutableStateOf(prefs.getBoolean("vpn_active", false)) }

                fun saveSession(s: AuthSession) {
                    prefs.edit().putString("access_token", s.accessToken).putString("refresh_token", s.refreshToken)
                        .putString("user_id", s.userId).putString("email", s.email).apply()
                    session = s
                }
                fun loadHome(s: AuthSession) {
                    lifecycleScope.launch {
                        loading = true; message = ""
                        try {
                            val data = withContext(Dispatchers.IO) {
                                val refreshed = if (s.accessToken.isBlank()) NetSwapApi.refresh(s.refreshToken) else s
                                Pair(refreshed, Pair(NetSwapApi.profileUid(refreshed), NetSwapApi.requests(refreshed)))
                            }
                            saveSession(data.first); uid = data.second.first; requests = data.second.second
                        } catch (e: Exception) { message = e.message ?: "Could not load account data." }
                        loading = false
                    }
                }

                LaunchedEffect(session?.userId) { session?.let { loadHome(it) } }

                if (session == null) {
                    AuthScreen(loading, message,
                        onLogin = { email, password ->
                            lifecycleScope.launch {
                                loading = true; message = ""
                                try { saveSession(withContext(Dispatchers.IO) { NetSwapApi.signIn(email, password) }) }
                                catch (e: Exception) { message = e.message ?: "Login failed." }
                                loading = false
                            }
                        },
                        onSignUp = { email, password ->
                            lifecycleScope.launch {
                                loading = true; message = ""
                                try {
                                    val result = withContext(Dispatchers.IO) { NetSwapApi.signUp(email, password) }
                                    if (result.optString("access_token").isNotBlank() && result.optJSONObject("user") != null) {
                                        val u = result.getJSONObject("user")
                                        saveSession(AuthSession(result.getString("access_token"), result.optString("refresh_token"), u.getString("id"), u.optString("email", email)))
                                    } else message = "Account created. Email verification may be required. Verify it, then log in."
                                } catch (e: Exception) { message = e.message ?: "Sign up failed." }
                                loading = false
                            }
                        })
                } else {
                    HomeScreen(session!!, uid, requests, loading, message,
                        onRefresh = { loadHome(session!!) },
                        vpnActive = vpnActive,
                        onVpnSetup = { startVpnWithNotificationPermission(); vpnActive = true },
                        onVpnDisconnect = { disconnectVpnService(); vpnActive = false },
                        onSend = { friendUid, quota ->
                            lifecycleScope.launch {
                                loading = true; message = ""
                                try {
                                    withContext(Dispatchers.IO) {
                                        val friend = NetSwapApi.lookupUid(session!!, friendUid)
                                            ?: throw IllegalArgumentException("No NetSwap user found with that UID.")
                                        NetSwapApi.sendRequest(session!!, friend.userId, quota)
                                    }
                                    message = "Sharing request sent. Your friend must accept it."
                                    loadHome(session!!)
                                } catch (e: Exception) { message = e.message ?: "Could not send request."; loading = false }
                            }
                        },
                        onRespond = { request, status ->
                            lifecycleScope.launch {
                                loading = true
                                try { withContext(Dispatchers.IO) { NetSwapApi.respond(session!!, request.id, status) }; loadHome(session!!) }
                                catch (e: Exception) { message = e.message ?: "Could not update request."; loading = false }
                            }
                        },
                        onLogout = { prefs.edit().clear().apply(); session = null; uid = ""; requests = emptyList(); message = "" }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(loading: Boolean, message: String, onLogin: (String,String)->Unit, onSignUp: (String,String)->Unit) {
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Cream) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("NetSwap", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("Share a little. Stay connected.", color = Muted)
            Spacer(Modifier.height(26.dp))
            Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(26.dp)).padding(20.dp)) {
                Text(if (signup) "Create your account" else "Welcome back", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (message.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(message, color = Color(0xFF8B5A36), fontSize = 13.sp) }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { if (signup) onSignUp(email.trim(), password) else onLogin(email.trim(), password) }, enabled = !loading && email.contains("@") && password.length >= 6, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (loading) "Please wait…" else if (signup) "Create account" else "Log in")
                }
                TextButton(onClick = { signup = !signup }) { Text(if (signup) "Already registered? Log in" else "New here? Create account") }
            }
        }
    }
}

@Composable
private fun HomeScreen(session: AuthSession, uid: String, requests: List<ShareRequest>, loading: Boolean, message: String,
    vpnActive: Boolean, onRefresh: () -> Unit, onVpnSetup: () -> Unit, onVpnDisconnect: () -> Unit,
    onSend: (String,Long)->Unit, onRespond: (ShareRequest,String)->Unit, onLogout: ()->Unit) {
    var friendUid by remember { mutableStateOf("") }
    var quota by remember { mutableStateOf(500L * 1024 * 1024) }
    var showSend by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = Cream) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("NetSwap", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink); Text("Share a little. Stay connected.", fontSize = 13.sp, color = Muted) }
                Box(Modifier.background(Lavender, CircleShape).padding(horizontal = 13.dp, vertical = 8.dp)) { Text("BETA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF65568F)) }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(24.dp)).padding(18.dp)) {
                Text("Your NetSwap ID", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(if (uid.isBlank()) "Loading your ID…" else uid, color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(session.email, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text("Account connected • Sharing is not active yet", color = Color(0xFF3E725B), fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showSend = !showSend }, modifier = Modifier.weight(1f)) { Text("Send data request") }
                OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
            }
            if (vpnActive) {
                OutlinedButton(onClick = onVpnDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect VPN") }
                Text("Test VPN route connected • Internet sharing is not active yet.", color = Muted, fontSize = 11.sp)
            } else {
                OutlinedButton(onClick = onVpnSetup, modifier = Modifier.fillMaxWidth()) { Text("Connect VPN (test)") }
                Text("VPN foundation only: this test route does not share or proxy internet yet.", color = Muted, fontSize = 11.sp)
            }
            if (showSend) {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).background(White, RoundedCornerShape(20.dp)).padding(16.dp)) {
                    Text("Friend's NetSwap UID", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(friendUid, { friendUid = it.uppercase() }, placeholder = { Text("NS-XXXXXXXXXX") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Data quota")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100L to "100 MB", 500L to "500 MB", 1024L to "1 GB").forEach { (mb,label) ->
                            FilterChip(selected = quota == mb*1024*1024, onClick = { quota = mb*1024*1024 }, label = { Text(label) })
                        }
                    }
                    Button(onClick = { onSend(friendUid, quota); showSend = false }, enabled = !loading && friendUid.startsWith("NS-") && friendUid.length >= 8, modifier = Modifier.fillMaxWidth()) { Text("Send request") }
                }
            }
            if (message.isNotBlank()) { Text(message, color = Color(0xFF8B5A36), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            Text("Sharing requests", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(requests, key = { it.id }) { r ->
                    val incoming = r.receiverId == session.userId
                    Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(18.dp)).padding(14.dp)) {
                        Text(if (incoming) "Incoming request" else "You sent a request", fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("${r.quotaBytes / (1024*1024)} MB • ${r.status.uppercase()}", color = Muted, fontSize = 12.sp)
                        if (incoming && r.status == "pending") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onRespond(r, "approved") }, modifier = Modifier.weight(1f)) { Text("Accept") }
                                OutlinedButton(onClick = { onRespond(r, "rejected") }, modifier = Modifier.weight(1f)) { Text("Decline") }
                            }
                        } else if (!incoming && r.status in listOf("pending","approved")) {
                            TextButton(onClick = { onRespond(r, "cancelled") }) { Text("Cancel request") }
                        }
                    }
                }
            }
            TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Log out") }
        }
    }
}
