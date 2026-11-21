package com.netswap.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.VpnService
import android.widget.Toast
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Cream = Color(0xFFF8F6F0)
private val Ink = Color(0xFF252A32)
private val Muted = Color(0xFF7C8490)
private val Lavender = Color(0xFFE8E2FA)
private val Mint = Color(0xFFDDF2E8)
private val White = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    private var qrScanCallback: ((String) -> Unit)? = null

    private fun scanNetSwapQr(onResult: (String) -> Unit) {
        qrScanCallback = onResult
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue.orEmpty().trim().uppercase()
                if (value.matches(Regex("NS-[A-Z0-9-]{5,13}"))) qrScanCallback?.invoke(value)
                else Toast.makeText(this, "This QR is not a valid NetSwap ID.", Toast.LENGTH_LONG).show()
                qrScanCallback = null
            }
            .addOnCanceledListener { qrScanCallback = null }
            .addOnFailureListener { error ->
                qrScanCallback = null
                Toast.makeText(this, error.message ?: "QR scanner could not start.", Toast.LENGTH_LONG).show()
            }
    }
    private val vpnState = mutableStateOf(false)
    private val peerStatus = mutableStateOf("Ready — no active peer connection.")
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NetSwapVpnService.ACTION_STATE -> vpnState.value = intent.getBooleanExtra(NetSwapVpnService.EXTRA_ACTIVE, false)
                NetSwapUserspaceVpnService.ACTION_STATE -> {
                    vpnState.value = intent.getBooleanExtra(NetSwapUserspaceVpnService.EXTRA_ACTIVE, false)
                    intent.getStringExtra(NetSwapUserspaceVpnService.EXTRA_ERROR)?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                }
                NetSwapPeerProxyService.ACTION_STATE -> {
                    vpnState.value = intent.getBooleanExtra(NetSwapPeerProxyService.EXTRA_ACTIVE, false)
                    peerStatus.value = intent.getStringExtra(NetSwapPeerProxyService.EXTRA_STATUS) ?: "Connection status unavailable."
                    if (!vpnState.value && listOf("failed", "could not", "required", "not found", "cancelled", "needs an active").any { peerStatus.value.contains(it, ignoreCase = true) }) Toast.makeText(this@MainActivity, peerStatus.value, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestVpnSetup()
    }
    private val wireGuardConfigPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importWireGuardConfig(uri)
    }

    private fun importWireGuardConfig(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Could not read the selected file.")
                }
                require(bytes.size in 1..65536) { "Config file must be smaller than 64 KB." }
                withContext(Dispatchers.IO) {
                    Config.parse(ByteArrayInputStream(bytes))
                    openFileOutput("netswap-wg.conf", MODE_PRIVATE).use { it.write(bytes) }
                }
                Toast.makeText(this@MainActivity, "WireGuard config imported securely on this phone.", Toast.LENGTH_LONG).show()
                startVpnWithNotificationPermission()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Invalid WireGuard config.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startVpnWithNotificationPermission() {
        if (!getFileStreamPath("netswap-wg.conf").exists()) {
            wireGuardConfigPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            return
        }
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
    }

    private fun disconnectVpnService() {
        startService(Intent(this, NetSwapVpnService::class.java).setAction(NetSwapVpnService.ACTION_STOP))
        startService(Intent(this, NetSwapPeerProxyService::class.java).setAction(NetSwapPeerProxyService.ACTION_STOP))
        startService(Intent(this, NetSwapUserspaceVpnService::class.java).setAction(NetSwapUserspaceVpnService.ACTION_STOP))
        vpnState.value = false
        prefs.edit().putBoolean("vpn_active", false).apply()
    }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
    }

    private var pendingTunnelRequestId: String? = null
    private val userspaceVpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingTunnelRequestId
        pendingTunnelRequestId = null
        if (result.resultCode == RESULT_OK && id != null) launchUserspaceVpn(id)
        else Toast.makeText(this, "VPN permission is required.", Toast.LENGTH_LONG).show()
    }

    private fun launchUserspaceVpn(id: String) {
        val intent = Intent(this, NetSwapPeerProxyService::class.java)
            .setAction(NetSwapPeerProxyService.ACTION_START)
            .putExtra(NetSwapPeerProxyService.EXTRA_REQUEST_ID, id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun requestUserspaceTunnel(request: ShareRequest) {
        pendingTunnelRequestId = request.id
        val isReceiver = prefs.getString("user_id", null) == request.senderId
        val prepare = if (isReceiver) VpnService.prepare(this) else null
        if (prepare != null) userspaceVpnPermissionLauncher.launch(prepare)
        else {
            pendingTunnelRequestId = null
            launchUserspaceVpn(request.id)
        }
    }

    private fun requestVpnSetup() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
        else startVpnService()
    }

    private val prefs by lazy { getSharedPreferences("netswap_session", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stateFilter = IntentFilter().apply {
            addAction(NetSwapVpnService.ACTION_STATE)
            addAction(NetSwapUserspaceVpnService.ACTION_STATE)
            addAction(NetSwapPeerProxyService.ACTION_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnStateReceiver, stateFilter)
        }
        vpnState.value = NetSwapVpnService.isTunnelActive
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
                var displayName by remember { mutableStateOf("") }
                var requests by remember { mutableStateOf(emptyList<ShareRequest>()) }
                var loading by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                var vpnActive by vpnState
                var livePeerStatus by peerStatus

                fun saveSession(s: AuthSession) {
                    prefs.edit().putString("access_token", s.accessToken).putString("refresh_token", s.refreshToken)
                        .putString("user_id", s.userId).putString("email", s.email).apply()
                    session = s
                }
                fun loadHome(s: AuthSession, clearMessage: Boolean = true) {
                    lifecycleScope.launch {
                        loading = true
                        if (clearMessage) message = ""
                        try {
                            val data = withContext(Dispatchers.IO) {
                                val refreshed = if (s.accessToken.isBlank()) NetSwapApi.refresh(s.refreshToken) else s
                                val name = NetSwapApi.displayName(refreshed)
                                Triple(refreshed, NetSwapApi.profileUid(refreshed), Pair(name, NetSwapApi.requests(refreshed)))
                            }
                            saveSession(data.first); uid = data.second; displayName = data.third.first; requests = data.third.second
                        } catch (e: Exception) { message = e.message ?: "Could not load account data." }
                        loading = false
                    }
                }

                LaunchedEffect(session?.userId) {
                    val activeSession = session
                    if (activeSession != null) {
                        loadHome(activeSession)
                        while (true) {
                            delay(20_000)
                            try {
                                val latest = withContext(Dispatchers.IO) { NetSwapApi.requests(activeSession) }
                                requests = latest
                            } catch (_: Exception) {
                                // Keep the last known request state during brief network interruptions.
                            }
                        }
                    }
                }

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
                    HomeScreen(session!!, uid, displayName, requests, loading, message,
                        onRefresh = { loadHome(session!!) },
                        onSaveName = { name -> lifecycleScope.launch { loading = true; try { withContext(Dispatchers.IO) { NetSwapApi.updateDisplayName(session!!, name) }; displayName = name.trim(); message = "Username saved." } catch (e: Exception) { message = e.message ?: "Username save failed." }; loading = false } },
                        onDelete = { request -> lifecycleScope.launch { loading = true; try { withContext(Dispatchers.IO) { NetSwapApi.deleteRequest(session!!, request.id) }; loadHome(session!!) } catch (e: Exception) { message = e.message ?: "Request delete failed."; loading = false } } },
                        vpnActive = vpnActive,
                        peerStatus = livePeerStatus,
                        onVpnSetup = { startVpnWithNotificationPermission() },
                        onVpnDisconnect = { disconnectVpnService(); vpnActive = false },
                        onSend = { friendUid, quota ->
                            lifecycleScope.launch {
                                loading = true; message = ""
                                try {
                                    withContext(Dispatchers.IO) {
                                        val friend = NetSwapApi.lookupUid(session!!, friendUid)
                                            ?: throw IllegalArgumentException("No NetSwap user found with that UID.")
                                        require(friend.userId != session!!.userId) { "You cannot send a request to your own NetSwap ID." }
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
                        onStartTunnel = { request -> requestUserspaceTunnel(request) },
                        onScanUid = { callback -> scanNetSwapQr(callback) },
                        onLogout = { prefs.edit().clear().apply(); session = null; uid = ""; requests = emptyList(); message = "" }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(vpnStateReceiver) }
        super.onDestroy()
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
private fun HomeScreen(
    session: AuthSession,
    uid: String,
    displayName: String,
    requests: List<ShareRequest>,
    loading: Boolean,
    message: String,
    vpnActive: Boolean,
    peerStatus: String,
    onRefresh: () -> Unit,
    onSaveName: (String) -> Unit,
    onDelete: (ShareRequest) -> Unit,
    onVpnSetup: () -> Unit,
    onVpnDisconnect: () -> Unit,
    onSend: (String, Long) -> Unit,
    onRespond: (ShareRequest, String) -> Unit,
    onStartTunnel: (ShareRequest) -> Unit,
    onScanUid: ((String) -> Unit) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var friendUid by remember { mutableStateOf("") }
    var usernameInput by remember(displayName) { mutableStateOf(displayName) }
    var amountMb by remember { mutableStateOf("100") }
    var activeTab by remember(displayName) { mutableStateOf(if (displayName.isBlank()) "profile" else "home") }
    val incoming = requests.filter { it.receiverId == session.userId }
    val outgoing = requests.filter { it.senderId == session.userId }
    val pendingCount = incoming.count { it.status == "pending" }
    val approvedCount = requests.count { it.status == "approved" }

    Surface(Modifier.fillMaxSize(), color = Cream) {
        Column(Modifier.fillMaxSize()) {
            // Persistent app header and one-tap profile access.
            Row(
                Modifier.fillMaxWidth().background(White).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("NetSwap", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Text("Share internet. Stay connected.", fontSize = 11.sp, color = Muted)
                }
                TextButton(onClick = { activeTab = "profile" }) {
                    Text("◉  Profile", color = Color(0xFF6756A5), fontWeight = FontWeight.SemiBold)
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                    .then(if (activeTab == "requests" || activeTab == "sent") Modifier else Modifier.verticalScroll(rememberScrollState()))
            ) {
                when (activeTab) {
                    "profile" -> {
                        Spacer(Modifier.height(18.dp))
                        Text("Your profile", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("Manage your NetSwap identity and account.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(18.dp))
                        Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(24.dp)).padding(18.dp)) {
                            Box(
                                Modifier.size(62.dp).background(Lavender, CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text("👤", fontSize = 27.sp) }
                            Spacer(Modifier.height(14.dp))
                            Text("Username", fontWeight = FontWeight.SemiBold, color = Ink)
                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { usernameInput = it.take(24) },
                                label = { Text("Choose your username") },
                                placeholder = { Text("2–24 characters") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Your username is saved to your account. You can edit it here anytime.", color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onSaveName(usernameInput.trim()) },
                                enabled = !loading && usernameInput.trim().length in 2..24 && usernameInput.trim() != displayName,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(15.dp)
                            ) { Text(if (loading) "Saving…" else "Save username") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(20.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Your NetSwap ID", color = Muted, fontSize = 12.sp)
                            Text(if (uid.isBlank()) "Loading…" else uid, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text("Friend can scan this QR to read your ID.", color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))
                            if (uid.isNotBlank()) {
                                val qrBitmap = remember(uid) {
                                    val matrix = QRCodeWriter().encode(uid, BarcodeFormat.QR_CODE, 640, 640)
                                    Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).also { bitmap ->
                                        for (x in 0 until 640) for (y in 0 until 640) {
                                            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                                        }
                                    }
                                }
                                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "NetSwap ID QR code", modifier = Modifier.size(210.dp))
                            } else {
                                CircularProgressIndicator()
                            }
                            Text("QR contains only your public NetSwap ID, not login or session credentials.", color = Muted, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText("NetSwap ID", uid))
                                    Toast.makeText(context, "NetSwap ID copied.", Toast.LENGTH_SHORT).show()
                                }, enabled = uid.isNotBlank()) { Text("Copy ID") }
                                OutlinedButton(onClick = {
                                    context.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "My NetSwap ID: $uid"),
                                        "Share NetSwap ID"
                                    ))
                                }, enabled = uid.isNotBlank()) { Text("Share ID") }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
                    }
                    "join" -> {
                        Spacer(Modifier.height(18.dp))
                        Text("Join a connection", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("Request internet from someone you trust.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(22.dp)).padding(17.dp)) {
                            Text("Friend's NetSwap ID", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                friendUid,
                                { friendUid = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(16) },
                                label = { Text("NS-XXXXXXXXXX") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(onClick = { onScanUid { scanned -> friendUid = scanned } }, modifier = Modifier.fillMaxWidth()) {
                                Text("Scan friend's QR code")
                            }
                            Text("Or enter the ID manually. QR scan needs Google Code Scanner services.", color = Muted, fontSize = 10.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("Data limit", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                amountMb,
                                { value -> amountMb = value.filter(Char::isDigit).take(7) },
                                label = { Text("Amount in MB") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("50", "100", "250").forEach { value ->
                                    FilterChip(selected = amountMb == value, onClick = { amountMb = value }, label = { Text("$value MB") })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            val parsedMb = amountMb.toLongOrNull() ?: 0L
                            Button(
                                onClick = { onSend(friendUid.trim(), parsedMb * 1024L * 1024L) },
                                enabled = !loading && displayName.isNotBlank() && friendUid.startsWith("NS-") && friendUid.length >= 8 && parsedMb in 1..1048576,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(15.dp)
                            ) { Text(if (loading) "Sending…" else "Send request") }
                            Text("The other person must accept before a connection can start.", color = Muted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Your sent requests are in the Sent tab below.", color = Muted, fontSize = 12.sp)
                    }
                    "sent" -> {
                        Spacer(Modifier.height(18.dp))
                        Text("Sent requests", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("People you asked for internet. Approved requests can connect here.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        if (outgoing.isEmpty()) {
                            Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(20.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📤", fontSize = 30.sp)
                                Text("No sent requests yet", fontWeight = FontWeight.SemiBold, color = Ink)
                                Text("Send a request from Join and it will appear here.", color = Muted, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                items(outgoing, key = { it.id }) { r ->
                                    RequestCard(
                                        title = "To: ${r.receiverName}",
                                        detail = "${r.quotaBytes / (1024 * 1024)} MB • ${r.status.uppercase()}",
                                        request = r,
                                        incoming = false,
                                        loading = loading,
                                        onRespond = onRespond,
                                        onDelete = onDelete,
                                        onStartTunnel = onStartTunnel,
                                        peerStatus = peerStatus
                                    )
                                }
                            }
                        }
                    }
                    "requests" -> {
                        Spacer(Modifier.height(18.dp))
                        Text("Requests", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("Accept requests or manage your connections.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        if (incoming.isEmpty()) {
                            Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(20.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📥", fontSize = 30.sp)
                                Text("No incoming requests", fontWeight = FontWeight.SemiBold, color = Ink)
                                Text("New requests will appear here.", color = Muted, fontSize = 12.sp)
                            }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(incoming, key = { it.id }) { r ->
                                RequestCard(
                                    title = "From: ${r.senderName}",
                                    detail = "${r.quotaBytes / (1024 * 1024)} MB • ${r.status.uppercase()}",
                                    request = r,
                                    incoming = true,
                                    loading = loading,
                                    onRespond = onRespond,
                                    onDelete = onDelete,
                                    onStartTunnel = onStartTunnel,
                                    peerStatus = peerStatus
                                )
                            }
                        }
                    }
                    else -> {
                        Spacer(Modifier.height(18.dp))
                        Text("Good to see you, ${displayName.ifBlank { "there" }}", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("Your internet-sharing dashboard", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().background(Color(0xFF29233F), RoundedCornerShape(24.dp)).padding(19.dp)) {
                            Text("NETSWAP CONNECTION", color = Color(0xFFD7CFF7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(if (vpnActive) "Connected" else "Ready when you are", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Text(peerStatus, color = Color(0xFFE1DDED), fontSize = 12.sp)
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { if (vpnActive) onVpnDisconnect() else onRefresh() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBDB0F2), contentColor = Color(0xFF211B35)),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text(if (vpnActive) "Disconnect" else "Refresh status") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DashboardStat("Incoming", incoming.size.toString(), Modifier.weight(1f))
                            DashboardStat("Sent", outgoing.size.toString(), Modifier.weight(1f))
                            DashboardStat("Accepted", approvedCount.toString(), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Quick actions", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f).background(White, RoundedCornerShape(18.dp)).padding(14.dp)) {
                                Text("📡", fontSize = 24.sp)
                                Text("Join", fontWeight = FontWeight.Bold, color = Ink)
                                Text("Request internet", color = Muted, fontSize = 11.sp)
                                TextButton(onClick = { activeTab = "join" }) { Text("Get started") }
                            }
                            Column(Modifier.weight(1f).background(White, RoundedCornerShape(18.dp)).padding(14.dp)) {
                                Text("🤝", fontSize = 24.sp)
                                Text("Share", fontWeight = FontWeight.Bold, color = Ink)
                                Text("Review requests", color = Muted, fontSize = 11.sp)
                                TextButton(onClick = { activeTab = "requests" }) { Text("View requests") }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(18.dp)).padding(15.dp)) {
                            Text("Your NetSwap ID", fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(if (uid.isBlank()) "Loading…" else uid, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6756A5))
                            Text("Share this ID with a friend to connect.", fontSize = 11.sp, color = Muted)
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(18.dp)).padding(15.dp)) {
                            Text("Data balance", fontWeight = FontWeight.SemiBold, color = Ink)
                            Text("Operator balance is not available through Android right now. NetSwap won't show a guessed value.", fontSize = 11.sp, color = Muted)
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Refreshing…" else "Refresh requests") }
                    }
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = Color(0xFF8B5A36), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF0E4), RoundedCornerShape(12.dp)).padding(11.dp))
                }
            }

            NavigationBar(containerColor = White) {
                NavigationBarItem(selected = activeTab == "home", onClick = { activeTab = "home" }, icon = { Text("⌂") }, label = { Text("Home") })
                NavigationBarItem(selected = activeTab == "join", onClick = { activeTab = "join" }, icon = { Text("↗") }, label = { Text("Join") })
                NavigationBarItem(
                    selected = activeTab == "sent",
                    onClick = { activeTab = "sent" },
                    icon = { BadgedBox(badge = { if (outgoing.isNotEmpty()) Badge { Text(outgoing.size.toString()) } }) { Text("📤") } },
                    label = { Text("Sent") }
                )
                NavigationBarItem(
                    selected = activeTab == "requests",
                    onClick = { activeTab = "requests" },
                    icon = { BadgedBox(badge = { if (pendingCount > 0) Badge { Text(pendingCount.toString()) } }) { Text("⇄") } },
                    label = { Text("Requests") }
                )
            }
        }
    }
}

@Composable
private fun DashboardStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(White, RoundedCornerShape(17.dp)).padding(12.dp)) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(label, fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun RequestCard(
    title: String,
    detail: String,
    request: ShareRequest,
    incoming: Boolean,
    loading: Boolean,
    onRespond: (ShareRequest, String) -> Unit,
    onDelete: (ShareRequest) -> Unit,
    onStartTunnel: (ShareRequest) -> Unit,
    peerStatus: String
) {
    Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(detail, color = Muted, fontSize = 12.sp)
        if (request.status == "approved") {
            val quota = request.quotaBytes.coerceAtLeast(0L)
            val used = request.usedBytes.coerceIn(0L, quota)
            val remaining = (quota - used).coerceAtLeast(0L)
            val progress = if (quota > 0L) used.toFloat() / quota.toFloat() else 0f
            fun mb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Used ${mb(used)} MB", color = Muted, fontSize = 11.sp)
                Text("${mb(remaining)} MB left", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(7.dp),
                color = if (remaining == 0L) Color(0xFFCC665F) else Color(0xFF6B9B83),
                trackColor = Lavender
            )
            if (remaining == 0L) {
                Text("Data limit reached. This connection has no quota remaining.", color = Color(0xFFB24E48), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            request.status == "pending" && incoming -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRespond(request, "approved") }, enabled = !loading, modifier = Modifier.weight(1f)) { Text("Accept") }
                OutlinedButton(onClick = { onRespond(request, "rejected") }, enabled = !loading, modifier = Modifier.weight(1f)) { Text("Decline") }
            }
            request.status == "approved" -> {
                Text(if (incoming) "You accepted this request." else "Your request was accepted.", color = Color(0xFF3E725B), fontSize = 12.sp)
                ConnectionMonitorCard(peerStatus)
                val quotaAvailable = request.quotaBytes > 0L && request.usedBytes < request.quotaBytes
                Button(
                    onClick = { onStartTunnel(request) },
                    enabled = !loading && quotaAvailable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            !quotaAvailable -> "Data limit reached"
                            incoming -> "Start sharing"
                            else -> "Join & connect"
                        }
                    )
                }
            }
            request.status == "pending" && !incoming -> TextButton(onClick = { onRespond(request, "cancelled") }, enabled = !loading) { Text("Cancel request") }
            request.status in listOf("rejected", "cancelled") -> TextButton(onClick = { onDelete(request) }, enabled = !loading) { Text("Delete request") }
        }
    }
}


@Composable
private fun ConnectionMonitorCard(status: String) {
    val normalized = status.lowercase()
    val failed = listOf("failed", "could not", "unable", "error", "not found", "timed out", "timeout", "required", "cancelled", "needs an active", "probe failed").any { normalized.contains(it) }
    val connected = normalized.contains("vpn tunnel is running") || normalized.contains("internet probe passed")
    val stages = listOf(
        "Preparing secure connection" to listOf("starting connection", "preparing secure", "connecting"),
        "Peer / ICE connection" to listOf("peer link", "ice", "peer did not connect"),
        "Secure data channel" to listOf("data channel"),
        "Receiver VPN + packet tunnel" to listOf("vpn", "hev", "packet tunnel"),
        "Internet connectivity test" to listOf("internet", "probe")
    )
    val current = stages.indexOfLast { (_, keys) -> keys.any { normalized.contains(it) } }.coerceAtLeast(0)
    val cardColor = when {
        failed -> Color(0xFFFFEFED)
        connected -> Color(0xFFEAF6EF)
        else -> Color(0xFFF1EEF8)
    }
    val accent = when {
        failed -> Color(0xFFB6423A)
        connected -> Color(0xFF347653)
        else -> Color(0xFF6756A5)
    }
    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE CONNECTION MONITOR", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(when { failed -> "FAILED"; connected -> "CONNECTED"; else -> "IN PROGRESS" }, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(status, color = Ink, fontSize = 12.sp)
        Spacer(Modifier.height(9.dp))
        stages.forEachIndexed { index, stage ->
            val done = connected || (!failed && index < current)
            val active = !failed && !connected && index == current
            val marker = when { failed && index == current -> "✕"; done -> "✓"; active -> "●"; else -> "○" }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(marker, color = if (failed && index == current) Color(0xFFB6423A) else if (done) Color(0xFF347653) else if (active) accent else Muted, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(stage.first, color = if (done || active || (failed && index == current)) Ink else Muted, fontSize = 10.sp, fontWeight = if (active || (failed && index == current)) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        if (failed) {
            Spacer(Modifier.height(6.dp))
            Text("Failure detail is shown above. Check both phones' internet, sign-in and TURN/ICE network access before retrying.", color = accent, fontSize = 10.sp)
        } else if (connected) {
            Spacer(Modifier.height(4.dp))
            Text("Internet probe passed. Peer transport and receiver tunnel are active.", color = accent, fontSize = 10.sp)
        }
    }
}
