package org.wifiauditor.pro

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.launch
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Lan
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestWifiPermissions()
        prefs = getSharedPreferences("wifichat", Context.MODE_PRIVATE)
        setContent {
            WiFiAuditorTheme { WiFiAuditorApp() }
        }
    }

    companion object {
        lateinit var prefs: android.content.SharedPreferences
    }

    private fun requestWifiPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Permisos necesarios")
                .setMessage("WiFi Auditor Pro necesita acceso a la ubicacion para escanear redes WiFi, " +
                        "y permisos Bluetooth para el chat de respaldo via Bluetooth. " +
                        "No se usa tu ubicacion para ningun otro fin. Todos los datos se procesan localmente.")
                .setPositiveButton("Continuar") { _, _ ->
                    permLauncher.launch(needed.toTypedArray())
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }
}

@Composable
fun WiFiAuditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF2196F3),
            secondary = Color(0xFF1a237e),
            background = Color(0xFF0f0f1a),
            surface = Color(0xFF1a1a2e),
            onPrimary = Color.White,
            onBackground = Color(0xFFe0e0e0),
            onSurface = Color(0xFFe0e0e0)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiAuditorApp(vm: WiFiViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "Redes", "Router", "Dispositivos", "MS")
    val icons = listOf(Icons.Filled.Dashboard, Icons.Filled.Wifi, Icons.Filled.Router,
        Icons.Filled.Devices, Icons.Filled.ChatBubble)

    val context = LocalContext.current
    val prefs = try { MainActivity.prefs } catch(_: Exception) { null }
    val consentDone = remember { mutableStateOf(prefs?.getBoolean("privacy_consent", false) ?: false) }

    if (!consentDone.value) {
        PrivacyConsentDialog(onAccept = {
            prefs?.edit()?.putBoolean("privacy_consent", true)?.apply()
            consentDone.value = true
        })
    }

    val networks by vm.networks.collectAsState()
    val devices by vm.devices.collectAsState()
    val routerInfo by vm.routerInfo.collectAsState()
    val ports by vm.ports.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val status by vm.status.collectAsState()
    val wpsResult by vm.wpsResult.collectAsState()
    val vulnResult by vm.vulnResult.collectAsState()
    val pingResult by vm.pingResult.collectAsState()
    val speedTestResult by vm.speedTestResult.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val settings by vm.settings.collectAsState()
    val traceroute by vm.traceroute.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Auditor Pro", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { idx, label ->
                    NavigationBarItem(
                        icon = { Icon(icons[idx], contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            when (selectedTab) {
                0 -> DashboardScreen(networks, routerInfo, scanning, vm)
                1 -> NetworksScreen(networks, scanning, vm)
                2 -> RouterScreen(routerInfo, ports, wpsResult, vulnResult, traceroute, vm)
                 3 -> DevicesScreen(devices, pingResult, vm, context)
                4 -> MensajesScreen(context, vm)
            }
        }
    }
}

// ── DASHBOARD ──
@Composable
fun DashboardScreen(
    networks: List<org.wifiauditor.pro.wifi.WiFiNetwork>,
    router: org.wifiauditor.pro.RouterInfo,
    scanning: Boolean,
    vm: WiFiViewModel
) {
    val devices by vm.devices.collectAsState()
    val best = networks.minByOrNull { it.level }
    val worst = networks.maxByOrNull { it.level }
    val avgLevel = if (networks.isNotEmpty()) networks.map { it.level }.average().toInt() else 0
    val bestSec = networks.minByOrNull { it.score }
    val crit = networks.count { it.risk == "CRITICO" }
    val riesgo = networks.count { it.risk == "RIESGO" }
    val medio = networks.count { it.risk == "MEDIO" }
    val seguro = networks.count { it.risk == "SEGURO" }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Critico", crit, Color(0xFFf44336), Modifier.weight(1f))
                StatCard("Riesgo", riesgo, Color(0xFFFF9800), Modifier.weight(1f))
                StatCard("Medio", medio, Color(0xFFFFC107), Modifier.weight(1f))
                StatCard("Seguro", seguro, Color(0xFF4CAF50), Modifier.weight(1f))
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Resumen de red", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    InfoRow("Redes encontradas", "${networks.size}")
                    InfoRow("Dispositivos en red", "${devices.size}")
                    InfoRow("Senal promedio", "$avgLevel dBm")
                    InfoRow("Mejor senal", if (best != null) "${best.ssid} (${best.level} dBm)" else "N/A")
                    InfoRow("Router", "${router.vendor} / ${router.gateway}")
                    if (bestSec != null) InfoRow("Mejor seguridad", "${bestSec.auth} (${bestSec.score}/100)")
                }
            }
        }
        item {
            Button(
                onClick = { vm.scanNetworks() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !scanning
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Escaneando..." else "Escanear redes WiFi")
            }
        }
        if (networks.isNotEmpty()) {
            item { SignalChart(networks) }
        }

        if (networks.isNotEmpty()) {
            // Security distribution bar
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Distribucion de seguridad", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        val open = networks.count { it.auth == "OPEN" }
                        val wep = networks.count { it.auth == "WEP" }
                        val secured = networks.count { it.auth !in listOf("OPEN", "WEP") }
                        val total = networks.size.toFloat()
                        PieBar("Abierta", open, total, Color(0xFFf44336))
                        PieBar("WEP", wep, total, Color(0xFFFF9800))
                        PieBar("Segura", secured, total, Color(0xFF4CAF50))
                    }
                }
            }
            // Signal distribution
            item {
                val excellent = networks.count { it.level > -50 }
                val good = networks.count { it.level in -70..-50 }
                val weak = networks.count { it.level < -70 }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Distribucion de senal", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        PieBar("Excelente (>-50)", excellent, networks.size.toFloat(), Color(0xFF4CAF50))
                        PieBar("Buena (-50 a -70)", good, networks.size.toFloat(), Color(0xFFFFC107))
                        PieBar("Debil (<-70)", weak, networks.size.toFloat(), Color(0xFFf44336))
                    }
                }
            }
        }

        if (crit > 0) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFd32f2f).copy(alpha = 0.2f))) {
                    Text("  $crit red(es) en estado CRITICO. Revisa la pestana Redes.",
                        modifier = Modifier.padding(12.dp), color = Color(0xFFf44336))
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.2f))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Text("$count", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp, color = color)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PieBar(label: String, count: Int, total: Float, color: Color) {
    val pct = if (total > 0) (count / total * 100).toInt() else 0
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(22.dp).fillMaxWidth()) {
        Text(label, fontSize = 11.sp, modifier = Modifier.width(90.dp))
        Box(modifier = Modifier.weight(1f).height(14.dp)) {
            Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray.copy(alpha = 0.3f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(fraction = count / total).height(14.dp).background(color, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
        }
        Text("$count ($pct%)", fontSize = 11.sp, modifier = Modifier.width(50.dp).padding(start = 4.dp))
    }
}

@Composable
fun SignalChart(networks: List<org.wifiauditor.pro.wifi.WiFiNetwork>) {
    if (networks.isEmpty()) return
    val sorted = networks.sortedByDescending { it.level }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Potencia de senal", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            sorted.take(10).forEach { net ->
                val pct = ((net.level + 100) * 100 / 70).coerceIn(0, 100)
                val barColor = when {
                    net.level > -50 -> Color(0xFF4CAF50)
                    net.level > -70 -> Color(0xFFFFC107)
                    else -> Color(0xFFf44336)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(20.dp)) {
                    Text(net.ssid.take(12), fontSize = 10.sp, modifier = Modifier.width(80.dp))
                    Box(modifier = Modifier.weight(1f).height(12.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(12.dp).background(Color.DarkGray.copy(alpha = 0.3f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
                        Box(modifier = Modifier.fillMaxWidth(fraction = pct / 100f).height(12.dp).background(barColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
                    }
                    Text("${net.level} dBm", fontSize = 10.sp, modifier = Modifier.width(45.dp).padding(start = 4.dp))
                }
            }
        }
    }
}

// ── REDES ──
@Composable
fun NetworksScreen(
    networks: List<org.wifiauditor.pro.wifi.WiFiNetwork>,
    scanning: Boolean,
    vm: WiFiViewModel
) {
    val sortMode by vm.sortMode.collectAsState()
    var selectedNet by remember { mutableStateOf<org.wifiauditor.pro.wifi.WiFiNetwork?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.scanNetworks() },
                    modifier = Modifier.weight(1f),
                    enabled = !scanning
                ) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (scanning) "Escaneando..." else "Escanear", fontSize = 13.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("potencia" to "Seguridad", "senal" to "Senal", "nombre" to "Nombre").forEach { (mode, label) ->
                    FilterChip(
                        selected = sortMode == mode,
                        onClick = { vm.setSortMode(mode); vm.applySortMode() },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        items(networks) { net ->
            val riskColor = when (net.risk) {
                "CRITICO" -> Color(0xFFf44336)
                "RIESGO" -> Color(0xFFFF9800)
                "MEDIO" -> Color(0xFFFFC107)
                else -> Color(0xFF4CAF50)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { selectedNet = net }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(net.ssid, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(net.risk, color = riskColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${net.auth} | ${net.cipher}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Text("Senal: ${net.level} dBm | ${net.band} | Punt: ${net.score}/100", fontSize = 12.sp)
                    if (net.issues.isNotEmpty()) {
                        net.issues.take(1).forEach { Text(it, fontSize = 11.sp, color = riskColor) }
                    }
                }
            }
        }
    }

    // Dialog de detalle
    selectedNet?.let { net ->
        val vendor = vm.getVendor(net.bssid)
        AlertDialog(
            onDismissRequest = { selectedNet = null },
            title = { Text(net.ssid, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    InfoRow("BSSID", net.bssid)
                    InfoRow("Fabricante", vendor)
                    InfoRow("Seguridad", "${net.auth} / ${net.cipher}")
                    InfoRow("Senal", "${net.level} dBm")
                    InfoRow("Banda", net.band)
                    InfoRow("Frecuencia", "${net.frequency} MHz")
                    InfoRow("Puntaje", "${net.score}/100 (${net.risk})")
                    Spacer(Modifier.height(8.dp))
                    if (net.issues.isNotEmpty()) {
                        Text("Problemas:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        net.issues.forEach { Text("• $it", fontSize = 11.sp, color = Color(0xFFFF9800)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedNet = null }) { Text("Cerrar") } }
        )
    }
}

// ── ROUTER ──
@Composable
fun RouterScreen(
    router: org.wifiauditor.pro.RouterInfo,
    ports: List<org.wifiauditor.pro.PortResult>,
    wps: String, vulns: List<String>,
    traceroute: List<String>, vm: WiFiViewModel
) {
    val networks by vm.networks.collectAsState()
    var showToolsDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Router conectado (Gateway)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.analyzeRouter() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Router, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Analizar", fontSize = 13.sp)
            }
            Button(onClick = { vm.traceroute() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.SignalCellularAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Traceroute", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Gateway: ${router.gateway}")
                Text("MAC: ${router.mac}")
                Text("Fabricante: ${router.vendor}")
                Text("UPnP: $wps")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (traceroute.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Traceroute", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    traceroute.forEach { line ->
                        Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (vulns.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFd32f2f).copy(alpha = 0.15f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Vulnerabilidades", fontWeight = FontWeight.Bold, color = Color(0xFFf44336))
                    vulns.forEach { Text(it, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (ports.isNotEmpty()) {
            Text("Puertos abiertos en gateway", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            ports.forEach { p ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("  Puerto ${p.port} - ${p.service}", modifier = Modifier.padding(8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = { showToolsDialog = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) {
            Icon(Icons.Filled.Build, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Herramientas", fontSize = 14.sp)
        }
    }

    if (showToolsDialog) {
        ToolsDialog(vm = vm, context = context, onDismiss = { showToolsDialog = false })
    }
}

        if (networks.isNotEmpty()) {
            Text("Puntos de acceso detectados (${networks.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            networks.forEach { net ->
                val vendor = vm.getVendor(net.bssid)
                val riskColor = when (net.risk) {
                    "CRITICO" -> Color(0xFFf44336)
                    "RIESGO" -> Color(0xFFFF9800)
                    "MEDIO" -> Color(0xFFFFC107)
                    else -> Color(0xFF4CAF50)
                }
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(net.ssid, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(net.risk, color = riskColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Text(net.bssid, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text("Fabricante: $vendor | ${net.band} | ${net.auth} | ${net.cipher}", fontSize = 11.sp)
                        Text("Senal: ${net.level} dBm | Puntaje: ${net.score}/100", fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = { showToolsDialog = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) {
                Icon(Icons.Filled.Build, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Herramientas", fontSize = 14.sp)
            }
        }

        if (showToolsDialog) {
            ToolsDialog(vm = vm, context = context, onDismiss = { showToolsDialog = false })
        }
    }

    // ── DISPOSITIVOS ──
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DevicesScreen(
    devices: List<org.wifiauditor.pro.ConnectedDevice>,
    ping: org.wifiauditor.pro.PingResult?,
    vm: WiFiViewModel,
    context: Context = LocalContext.current
) {
    val speedTest by vm.speedTestResult.collectAsState()
    val deviceEvents by vm.deviceEvents.collectAsState()
    var selectedDevice by remember { mutableStateOf<org.wifiauditor.pro.ConnectedDevice?>(null) }
    var blockResult by remember { mutableStateOf<String?>(null) }
    var showBlockConfirm by remember { mutableStateOf<org.wifiauditor.pro.ConnectedDevice?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    var selectedMacForHistory by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.scanDevices() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Devices, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Escanear", fontSize = 13.sp)
            }
            Button(onClick = { vm.pingGateway() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.SignalCellularAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Ping", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (ping != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ping a ${ping.host}", fontWeight = FontWeight.Bold)
                    Text("Paquetes: ${ping.received}/${ping.sent} | Perdida: ${ping.lost}")
                    Text("Latencia media: ${"%.0f".format(ping.avgMs)} ms")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(onClick = { vm.speedTest() }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00))) {
            Icon(Icons.Filled.Speed, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp)); Text("Speed test", fontSize = 13.sp)
        }
        if (speedTest != null) {
            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEF6C00).copy(alpha = 0.15f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Speed test a ${speedTest!!.host}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Paquetes: ${speedTest!!.received}/5 | Perdida: ${speedTest!!.lost} | Latencia media: ${"%.0f".format(speedTest!!.avgMs)} ms")
                    val quality = when { speedTest!!.lost >= 3 -> "Mala"; speedTest!!.avgMs > 200 -> "Regular"; speedTest!!.avgMs > 80 -> "Buena"; else -> "Excelente" }
                    Text("Calidad: $quality", fontWeight = FontWeight.Bold,
                        color = when (quality) { "Mala" -> Color(0xFFf44336); "Regular" -> Color(0xFFFF9800); "Buena" -> Color(0xFFFFC107); else -> Color(0xFF4CAF50) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── Resumen ──
        val activeCount = devices.count { it.active }
        val inactiveCount = devices.count { !it.active }
        val unknownCount = devices.count { !it.isKnown }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Dispositivos", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.loadDeviceEvents(); showAudit = true }) { Text("Historial", fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("$activeCount activos", fontSize = 12.sp, color = Color(0xFF4CAF50))
            Text("$inactiveCount inactivos", fontSize = 12.sp, color = Color(0xFF757575))
            if (unknownCount > 0) Text("$unknownCount no aprobados", fontSize = 12.sp, color = Color(0xFFf44336))
        }
        Spacer(Modifier.height(6.dp))

        // ── Lista de dispositivos ──
        devices.forEach { d ->
            val riskColor = when (d.riskLabel) {
                "CRITICO" -> Color(0xFFf44336); "RIESGO" -> Color(0xFFFF9800)
                "MEDIO" -> Color(0xFFFFC107); else -> Color(0xFF4CAF50)
            }
            val bgColor = if (!d.active) Color(0xFF424242).copy(alpha = 0.3f)
                else if (!d.isKnown) riskColor.copy(alpha = 0.12f)
                else Color(0xFF1B5E20).copy(alpha = 0.1f)
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .combinedClickable(
                        onClick = { selectedDevice = d },
                        onLongClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("IP", d.ip))
                            vm.setStatus("IP ${d.ip} copiada")
                        }
                    ),
                colors = CardDefaults.cardColors(containerColor = bgColor)
            ) {
                Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (d.active) Icons.Filled.Phone else Icons.Filled.PhoneEnabled,
                        null, Modifier.size(20.dp),
                        tint = if (d.active) riskColor else Color(0xFF757575)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name.ifEmpty { d.ip }, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Row {
                            Text("${d.vendor} | ${d.riskLabel}", fontSize = 11.sp,
                                color = if (d.active) riskColor else Color(0xFF757575))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (d.active) "ACTIVO" else "INACTIVO", fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, color = if (d.active) Color(0xFF4CAF50) else Color(0xFF757575))
                        Text("${d.riskScore}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = riskColor)
                    }
                }
            }
        }
    }

    // ── Dialog de auditoria ──
    if (showAudit) {
        AlertDialog(
            onDismissRequest = { showAudit = false },
            title = { Text("Historial de eventos", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    if (deviceEvents.isEmpty()) {
                        item { Text("Sin eventos registrados", fontSize = 13.sp, color = Color(0xFF757575)) }
                    }
                    items(deviceEvents) { ev ->
                        val date = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ev.timestamp))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(date, fontSize = 10.sp, modifier = Modifier.width(65.dp), color = Color(0xFF757575))
                            Text("${ev.mac.take(8)}...", fontSize = 10.sp, modifier = Modifier.width(50.dp), fontFamily = FontFamily.Monospace)
                            Text(ev.event, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAudit = false }) { Text("Cerrar") } }
        )
    }

    // ── Block confirmation ──
    showBlockConfirm?.let { dev ->
        AlertDialog(
            onDismissRequest = { showBlockConfirm = null },
            title = { Text("Bloquear", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("${dev.name.ifEmpty { dev.ip }} (${dev.mac})")
                    Spacer(Modifier.height(8.dp))
                    Text("Intenta eliminar el dispositivo de la red. Si falla, accede al router para bloquear por MAC.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFd32f2f)),
                    onClick = { blockResult = vm.blockDevice(dev.ip, dev.mac); showBlockConfirm = null }) { Text("Bloquear") }
            },
            dismissButton = { TextButton(onClick = { showBlockConfirm = null }) { Text("Cancelar") } }
        )
    }

    blockResult?.let { result ->
        AlertDialog(
            onDismissRequest = { blockResult = null },
            title = { Text("Resultado", fontWeight = FontWeight.Bold) },
            text = { Text(result) },
            confirmButton = { TextButton(onClick = { blockResult = null }) { Text("OK") } }
        )
    }

    // ── Dialog de detalle ──
    selectedDevice?.let { d ->
        var editName by remember(d) { mutableStateOf(d.name) }
        var openPorts by remember { mutableStateOf<List<org.wifiauditor.pro.WiFiViewModel.OpenPort>?>(null) }
        var scanningPorts by remember { mutableStateOf(false) }

        LaunchedEffect(d.ip) {
            scanningPorts = true
            vm.quickPortScan(d.ip) { ports -> openPorts = ports; scanningPorts = false }
        }

        AlertDialog(
            onDismissRequest = { selectedDevice = null },
            title = { Text(d.name.ifEmpty { d.ip }, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("IP: ${d.ip}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("IP", d.ip))
                                vm.setStatus("IP ${d.ip} copiada")
                            }
                        ).fillMaxWidth())
                    InfoRow("MAC", d.mac); InfoRow("Fabricante", d.vendor)
                    InfoRow("Estado", if (d.active) "ACTIVO" else "INACTIVO")
                    InfoRow("Riesgo", "${d.riskLabel} (${d.riskScore})")
                    InfoRow("1a vez", java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(d.firstSeen)))
                    InfoRow("Ultima vez", java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(d.lastSeen)))
                    Spacer(Modifier.height(8.dp))

                    // ── Puertos abiertos ──
                    Text("Puertos:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (scanningPorts) {
                        Text("Escaneando...", fontSize = 11.sp, color = Color(0xFF757575))
                    } else if (openPorts != null && openPorts!!.isNotEmpty()) {
                        openPorts!!.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Icon(Icons.Filled.Lan, null, Modifier.size(16.dp), tint = Color(0xFF4CAF50))
                                Spacer(Modifier.width(8.dp))
                                Text("Puerto ${p.port} - ${p.service}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = { vm.launchServiceApp(context, d.ip, p.service) },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("Abrir", fontSize = 10.sp) }
                            }
                        }
                    } else {
                        Text("Ninguno", fontSize = 11.sp, color = Color(0xFF757575))
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = editName, onValueChange = { editName = it },
                        label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = {
                            if (editName != d.name) vm.setDeviceName(d.mac, editName)
                            if (d.isKnown) vm.unapproveDevice(d.mac) else vm.approveDevice(d.mac, d.ip, editName)
                            selectedDevice = null
                        }) {
                            Text(if (d.isKnown) "Desaprobar" else "Aprobar")
                        }
                        if (!d.isKnown) {
                            Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFd32f2f)),
                                onClick = { selectedDevice = null; showBlockConfirm = d }) { Text("Bloquear", color = Color.White) }
                        }
                        Button(onClick = {
                            if (editName != d.name) {
                                if (d.mac != "?") vm.setDeviceName(d.mac, editName)
                                else vm.setIpDeviceName(d.ip, editName)
                                vm.setStatus("Nombre guardado")
                            }
                            selectedDevice = null
                        }) { Text("Guardar") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selectedDevice = null }) { Text("Cerrar") } }
        )
    }
}

// ── HERRAMIENTAS ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MensajesScreen(
    context: Context,
    vm: WiFiViewModel
) {
    val chatMessages by vm.chatMessages.collectAsState()
    val chatServerRunning by vm.chatServerRunning.collectAsState()
    val chatRelayConnected by vm.chatRelayConnected.collectAsState()
    val prefs = try { MainActivity.prefs } catch(_: Exception) { null }
    var chatTargetIp by remember { mutableStateOf(prefs?.getString("target_ip", "") ?: "") }
    var chatRelayUrl by remember { mutableStateOf(prefs?.getString("relay_url", "wifi-auditor.onrender.com") ?: "wifi-auditor.onrender.com") }
    var chatRelayPort by remember { mutableStateOf(prefs?.getString("relay_port", "443") ?: "443") }
    var chatText by remember { mutableStateOf("") }
    var useRelay by remember { mutableStateOf(false) }

    val chatListState = rememberLazyListState()
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isAtBottom = remember { derivedStateOf {
        val info = chatListState.layoutInfo
        if (chatMessages.isEmpty()) true
        else info.visibleItemsInfo.any { it.index >= chatMessages.size - 1 }
    } }
    var unseenCount by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val btEnabled by vm.btEnabled.collectAsState()
    val btServerRunning by vm.btServerRunning.collectAsState()
    val btDeviceList by vm.btDeviceList.collectAsState()
    val selectedBtDevice by vm.selectedBtDevice.collectAsState()
    val callState by vm.callState.collectAsState()
    val callerName by vm.callerName.collectAsState()
    val myGuestId by vm.myGuestId.collectAsState()
    val guestList by vm.guestList.collectAsState()
    val btConnected by vm.btConnected.collectAsState()

    LaunchedEffect(Unit) { vm.initBluetooth(context) }

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            if (isAtBottom.value) {
                chatListState.animateScrollToItem(chatMessages.size - 1)
                unseenCount = 0
            } else {
                unseenCount++
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { chatListState.firstVisibleItemIndex }
            .collect { if (it >= chatMessages.size - 1) unseenCount = 0 }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF1F2C33), shadowElevation = 8.dp) {
                Row(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp).padding(bottom = 120.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = chatText, onValueChange = { chatText = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Mensaje...", color = Color(0xFF8696A0)) },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF00A884),
                            focusedContainerColor = Color(0xFF2A3942),
                            unfocusedContainerColor = Color(0xFF2A3942),
                        ),
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val target = if (useRelay) chatRelayUrl else chatTargetIp
                            if (target.isNotBlank() && chatText.isNotBlank()) {
                                vm.sendChatMessage(target, chatText); chatText = ""
                            }
                        }))
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(onClick = {
                        val target = if (useRelay) chatRelayUrl else chatTargetIp
                        vm.sendChatMessage(target, chatText); chatText = ""
                    }, modifier = Modifier.size(42.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF00A884)),
                        shape = CircleShape) {
                        Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

        // ── Mensajes del chat (estilo WhatsApp) ──
        Column(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B141A))) {
            // ── Compact Chat TCP connection bar ──
            var expanded by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A5F))) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Chat, null, Modifier.size(16.dp), tint = Color(0xFF64B5F6))
                        Spacer(Modifier.width(4.dp))
                        Text("Chat TCP", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(6.dp).background(
                            if (useRelay) (if (chatRelayConnected) Color(0xFF4CAF50) else Color(0xFF757575))
                            else (if (chatServerRunning) Color(0xFF4CAF50) else Color(0xFF757575)), CircleShape))
                        Spacer(Modifier.weight(1f))
                        FilterChip(
                            selected = useRelay,
                            onClick = {
                                useRelay = !useRelay
                                if (!useRelay) vm.disconnectRelay()
                                if (useRelay) vm.stopChatServer()
                            },
                            label = { Text(if (useRelay) "Remoto" else "Local", fontSize = 10.sp, color = Color.White) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = if (useRelay) Color(0xFF1565C0) else Color(0xFF388E3C),
                                selectedContainerColor = Color(0xFF1565C0)
                            ), modifier = Modifier.height(26.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = Color.White)
                        }
                    }
                    if (expanded) {
                        Divider(color = Color(0xFF455A64), modifier = Modifier.padding(vertical = 4.dp))
                        if (!useRelay) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (chatServerRunning) "Server: ${vm.chatLocalIp}" else "Server apagado",
                                    fontSize = 10.sp, color = Color(0xFFB0BEC5))
                                Spacer(Modifier.weight(1f))
                                Button(onClick = { if (chatServerRunning) vm.stopChatServer() else vm.startChatServer() },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (chatServerRunning) Color(0xFFd32f2f) else Color(0xFF388E3C))) {
                                    Text(if (chatServerRunning) "Detener" else "Iniciar", fontSize = 11.sp, color = Color.White)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(value = chatTargetIp, onValueChange = { chatTargetIp = it; prefs?.edit()?.putString("target_ip", it)?.apply() },
                                label = { Text("IP destino") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                placeholder = { Text("Ej: 192.168.1.50") }, textStyle = TextStyle(fontSize = 13.sp))
                            Spacer(Modifier.height(6.dp))
                            Divider(color = Color(0xFF455A64), modifier = Modifier.padding(vertical = 2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bluetooth, null, Modifier.size(16.dp), tint = if (btEnabled) Color(0xFF2196F3) else Color(0xFF757575))
                                Spacer(Modifier.width(4.dp))
                                Text("Bluetooth", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                Spacer(Modifier.width(4.dp))
                                Box(Modifier.size(5.dp).background(if (btEnabled) Color(0xFF4CAF50) else Color(0xFF757575), CircleShape))
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = { if (btServerRunning) vm.stopBtServer() else vm.startBtServer() },
                                    modifier = Modifier.height(26.dp),
                                    enabled = btEnabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (btServerRunning) Color(0xFFd32f2f) else Color(0xFF1565C0),
                                        disabledContainerColor = Color(0xFF37474F)
                                    )) {
                                    Text(if (btServerRunning) "Detener BT" else "Iniciar BT", fontSize = 10.sp,
                                        color = if (btEnabled) Color.White else Color(0xFF757575))
                                }
                                Spacer(Modifier.width(4.dp))
                                Button(onClick = { vm.scanBtDevices() }, modifier = Modifier.height(26.dp),
                                    enabled = btEnabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1565C0),
                                        disabledContainerColor = Color(0xFF37474F)
                                    )) {
                                    Text("Escanear", fontSize = 10.sp,
                                        color = if (btEnabled) Color.White else Color(0xFF757575))
                                }
                            }
                            if (btDeviceList.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                                    btDeviceList.forEach { (name, addr) ->
                                        val isSelected = selectedBtDevice?.second == addr
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                            if (isSelected) vm.clearBtSelection() else vm.selectBtDevice(addr, name)
                                        }.padding(vertical = 2.dp).background(if (isSelected) Color(0xFF1E3A5F) else Color.Transparent, RoundedCornerShape(4.dp))) {
                                            Icon(if (isSelected) Icons.Filled.Bluetooth else Icons.Filled.Devices, null, Modifier.size(14.dp), tint = if (isSelected) Color(0xFF2196F3) else Color(0xFF90CAF9))
                                            Spacer(Modifier.width(4.dp))
                                            Text("$name ($addr)", fontSize = 10.sp, color = if (isSelected) Color.White else Color(0xFFB0BEC5), modifier = Modifier.weight(1f))
                                            Text(if (isSelected) "Seleccionado" else "Elegir", fontSize = 9.sp, color = if (isSelected) Color(0xFF4CAF50) else Color(0xFF64B5F6))
                                        }
                                    }
                                }
                            }
                            if (selectedBtDevice != null) {
                                Spacer(Modifier.height(2.dp))
                                Text("Destino BT: ${selectedBtDevice?.first}", fontSize = 10.sp, color = Color(0xFF64B5F6))
                            }
                        } else {
                            var guestName by remember { mutableStateOf(prefs?.getString("guest_name", "Telefono") ?: "Telefono") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (chatRelayConnected) "Conectado al relay" else "Desconectado",
                                    fontSize = 10.sp, color = Color(0xFFB0BEC5))
                                Spacer(Modifier.weight(1f))
                                Button(onClick = {
                                    if (chatRelayConnected) vm.disconnectRelay()
                                    else vm.connectRelay(chatRelayUrl, chatRelayPort.toIntOrNull() ?: 443, guestName)
                                }, modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (chatRelayConnected) Color(0xFFd32f2f) else Color(0xFF1565C0))) {
                                    Text(if (chatRelayConnected) "Desconectar" else "Conectar", fontSize = 11.sp, color = Color.White)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = guestName, onValueChange = { guestName = it; prefs?.edit()?.putString("guest_name", it)?.apply(); vm.setGuestName(it) },
                                    modifier = Modifier.weight(1f), singleLine = true,
                                    label = { Text("Tu apodo") }, textStyle = TextStyle(fontSize = 13.sp))
                                Spacer(Modifier.width(4.dp))
                                Button(onClick = { vm.clearChat() }, modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF546E7A))) {
                                    Icon(Icons.Filled.Delete, null, Modifier.size(14.dp), tint = Color.White)
                                }
                            }
                            if (chatRelayConnected && guestList.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Divider(color = Color(0xFF455A64), modifier = Modifier.padding(vertical = 1.dp))
                                guestList.filter { it.id != myGuestId }.forEach { guest ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Icon(Icons.Filled.Person, null, Modifier.size(14.dp), tint = Color(0xFF90CAF9))
                                        Spacer(Modifier.width(4.dp))
                                        Text(guest.name, fontSize = 11.sp, color = Color.White, modifier = Modifier.weight(1f))
                                        Button(onClick = { vm.startCall(guest.id, guest.name) },
                                            modifier = Modifier.height(26.dp),
                                            enabled = callState.name == "Idle",
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))) {
                                            Icon(Icons.Filled.Phone, null, Modifier.size(12.dp), tint = Color.White)
                                            Spacer(Modifier.width(2.dp))
                                            Text("Llamar", fontSize = 9.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Active call bar
            if (callState.name == "Calling" || callState.name == "Connected") {
                Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF075E54)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PhoneInTalk, null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Llamada ${if (callState.name == "Calling") "llamando..." else "activa"}", color = Color.White, fontSize = 13.sp)
                            if (callerName.isNotEmpty()) Text(callerName, color = Color(0xFFB2DFDB), fontSize = 11.sp)
                        }
                        Button(onClick = { vm.hangup() }, modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) {
                            Icon(Icons.Filled.CallEnd, null, Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("Colgar", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
            if (chatMessages.isNotEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = chatListState, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                        items(count = chatMessages.size, key = { it }) { pos ->
                            val msg = chatMessages[pos]
                            val prev = if (pos > 0) chatMessages[pos - 1] else null
                            val isSystem = msg.text.startsWith("---")
                            val isMe = msg.fromMe
                            val consecutive = prev != null && prev.fromMe == isMe && prev.fromName == msg.fromName && !isSystem && !prev.text.startsWith("---")
                            if (prev == null || !isSameDay(prev.timestamp, msg.timestamp)) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF182229)) {
                                        Text(getDateLabel(msg.timestamp), color = Color(0xFF8696A0), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
                                    }
                                }
                            }
                            if (isSystem) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                    Text(msg.text.replace("---", "").trim(), color = Color(0xFF8696A0), fontSize = 12.sp, fontStyle = FontStyle.Italic)
                                }
                            } else {
                                val bg = if (isMe) Color(0xFF005C4B) else Color(0xFF202C33)
                                val timeStr = remember(msg.timestamp) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)) }
                                val topPad = if (consecutive) 1.dp else 3.dp
                                val tailTL = if (isMe || !consecutive) 16.dp else 4.dp
                                val tailBR = if (isMe) 4.dp else 16.dp
                                Box(Modifier.fillMaxWidth().padding(top = topPad, bottom = 1.dp), contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
                                    Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                                        if (!isMe && msg.fromName.isNotEmpty() && !consecutive) {
                                            Text(msg.fromName, color = Color(0xFF00A884), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                                        }
                                        Box(Modifier.widthIn(max = 290.dp).background(bg, shape = RoundedCornerShape(16.dp, 16.dp, tailTL, tailBR)).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
                                            Column {
                                                Text(msg.text, color = Color.White, fontSize = 15.sp)
                                                Spacer(Modifier.height(2.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                                                    Text(timeStr, color = Color(0xFF8696A0), fontSize = 11.sp)
                                                    if (isMe) {
                                                        Spacer(Modifier.width(3.dp))
                                                        Icon(if (msg.status == "delivered" || msg.status == "read") Icons.Filled.Done else Icons.Filled.Check, null, modifier = Modifier.size(14.dp), tint = if (msg.status == "read") Color(0xFF53BDEB) else Color(0xFF8696A0))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = unseenCount > 0, enter = slideInVertically { it }, exit = slideOutVertically { it },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) {
                        FloatingActionButton(onClick = {
                            coroutineScope.launch { chatListState.animateScrollToItem(chatMessages.size - 1) }
                            unseenCount = 0
                        }, containerColor = Color(0xFF00A884)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White)
                                if (unseenCount > 1) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("$unseenCount", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Sin mensajes", fontSize = 13.sp, color = Color(0xFF8696A0))
                }
            }
        } // fin chat section Column
    } // fin outer Column
    } // fin Scaffold
    // Incoming call dialog
    if (callState.name == "Ringing") {
        AlertDialog(
            onDismissRequest = { vm.rejectCall() },
            title = { Text("Llamada entrante", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PhoneInTalk, null, Modifier.size(48.dp), tint = Color(0xFF4CAF50))
                    Spacer(Modifier.height(8.dp))
                    Text(callerName.ifEmpty { "Invitado" }, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("te esta llamando...", fontSize = 14.sp, color = Color(0xFF8696A0))
                }
            },
            confirmButton = {
                Button(onClick = { vm.answerCall() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                    Icon(Icons.Filled.Phone, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Responder")
                }
            },
            dismissButton = {
                Button(onClick = { vm.rejectCall() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) {
                    Icon(Icons.Filled.CallEnd, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rechazar")
                }
            }
        )
    }
}

@Composable
fun ToolsDialog(vm: WiFiViewModel, context: Context, onDismiss: () -> Unit) {
    val passwords by vm.passwords.collectAsState()
    val monitorChanges by vm.monitorChanges.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
        title = { Text("Herramientas", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Generador de contrasenas", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Button(onClick = { vm.generatePasswords() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(8.dp)); Text("Generar 4 contrasenas seguras")
                }
                passwords.forEach { (pw, bits) ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1a237e))) {
                        Text(pw, modifier = Modifier.padding(12.dp), fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("  ~${bits} bits", modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Exportar reporte", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { saveReport(context, vm, "html") }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))) {
                        Icon(Icons.Filled.FileDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp)); Text("HTML", fontSize = 11.sp)
                    }
                    Button(onClick = { saveReport(context, vm, "csv") }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) {
                        Icon(Icons.Filled.FileDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp)); Text("CSV", fontSize = 11.sp)
                    }
                    Button(onClick = { saveReport(context, vm, "json") }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))) {
                        Icon(Icons.Filled.FileDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp)); Text("JSON", fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val settings by vm.settings.collectAsState()
                Text("Configuracion", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Notificaciones", modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Switch(checked = settings.notificationsEnabled, onCheckedChange = { vm.toggleNotifications() })
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Vibrar al recibir mensaje", modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Switch(checked = settings.vibrateOnMessage, onCheckedChange = { vm.toggleVibrateOnMessage() })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Intervalo: ${settings.scanIntervalSec}s", fontSize = 13.sp)
                        Slider(value = settings.scanIntervalSec.toFloat(),
                            onValueChange = { vm.setScanInterval(it.toInt()) },
                            valueRange = 5f..60f, steps = 10)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/tuusuario/wifi-auditor-pro/blob/main/PRIVACY_POLICY.md")
                    })
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))) {
                    Icon(Icons.Filled.Shield, null); Spacer(Modifier.width(8.dp)); Text("Politica de privacidad")
                }
                Spacer(Modifier.height(12.dp))
                Text("Monitor continuo", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startMonitor() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("Iniciar", fontSize = 12.sp)
                    }
                    Button(onClick = { vm.stopMonitor() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFd32f2f))) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("Detener", fontSize = 12.sp)
                    }
                }
                monitorChanges.forEach { msg ->
                    val color = if (msg.startsWith("+")) Color(0xFF4CAF50)
                               else if (msg.startsWith("-")) Color(0xFFf44336)
                               else Color.White
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(msg, modifier = Modifier.padding(6.dp), fontSize = 11.sp, color = color,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

private fun saveReport(context: Context, vm: WiFiViewModel, format: String = "html") {
    val content = when (format) {
        "csv" -> vm.generateCSV()
        "json" -> vm.generateJSON()
        else -> vm.generateReport()
    }
    val ext = format
    val mime = when (format) {
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "text/html"
    }
    val fileName = "wifi_report_${System.currentTimeMillis()}.$ext"

    try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                vm.setStatus("Reporte guardado en Downloads/$fileName")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Abrir reporte"))
                return
            }
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, fileName)
        file.writeText(content)
        vm.setStatus("Reporte guardado en Downloads/$fileName")

    } catch (e: Exception) {
        vm.setStatus("Error: ${e.message}")
    }
}

@Composable
fun PrivacyConsentDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Privacidad", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("WiFi Auditor Pro respeta tu privacidad.\n\n" +
                    "Esta aplicacion:\n\n" +
                    "• Escanea redes WiFi cercanas para propositos educativos y de diagnostico.\n\n" +
                    "• Accede a la ubicacion (requisito de Android para escanear WiFi). No se almacena ni transmite tu ubicacion.\n\n" +
                    "• Detecta dispositivos en tu red local (IP, MAC, fabricante). Esta informacion no sale de tu dispositivo.\n\n" +
                    "• El chat TCP local funciona sin internet, directamente entre dispositivos en la misma red.\n\n" +
                    "• El chat remoto via WebSocket solo transmite los mensajes que envias voluntariamente.\n\n" +
                    "• NO recopila datos personales.\n" +
                    "• NO comparte datos con terceros.\n" +
                    "• NO usa publicidad.\n" +
                    "• Todos los datos de escaneo se procesan localmente.\n\n" +
                    "Al aceptar, confirmas que usaras esta herramienta unicamente en redes propias o con autorizacion explicita del propietario.")
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("Aceptar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {}
    )
}

private fun isSameDay(t1: Long, t2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

private fun getDateLabel(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
    return when {
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR) -> "Hoy"
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) - msg.get(Calendar.DAY_OF_YEAR) == 1 -> "Ayer"
        else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
