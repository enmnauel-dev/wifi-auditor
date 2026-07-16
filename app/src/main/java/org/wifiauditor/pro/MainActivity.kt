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
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            // Mostrar explicacion antes de pedir permisos (requisito Google Play)
            AlertDialog.Builder(this)
                .setTitle("Permiso necesario")
                .setMessage("WiFi Auditor Pro necesita acceso a la ubicacion para escanear redes WiFi cercanas. " +
                        "No se usa tu ubicacion para ningun otro fin. Todos los datos se procesan localmente en tu dispositivo.")
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
    val tabs = listOf("Dashboard", "Redes", "Router", "Dispositivos", "Herramientas")
    val icons = listOf(Icons.Filled.Dashboard, Icons.Filled.Wifi, Icons.Filled.Router,
        Icons.Filled.Devices, Icons.Filled.Build)

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
    val passwords by vm.passwords.collectAsState()
    val monitorChanges by vm.monitorChanges.collectAsState()
    val context = LocalContext.current

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
                4 -> ToolsScreen(passwords, monitorChanges, context, vm)
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
        }
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
fun ToolsScreen(
    passwords: List<Pair<String, Int>>,
    monitorChanges: List<String>,
    context: Context,
    vm: WiFiViewModel
) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {

        // ── Variables del chat ──
        val chatMessages by vm.chatMessages.collectAsState()
        val chatServerRunning by vm.chatServerRunning.collectAsState()
        val chatRelayConnected by vm.chatRelayConnected.collectAsState()
        val guestList by vm.guestList.collectAsState()
        val prefs = try { MainActivity.prefs } catch(_: Exception) { null }
        var chatTargetIp by remember { mutableStateOf(prefs?.getString("target_ip", "") ?: "") }
        var chatRelayUrl by remember { mutableStateOf(prefs?.getString("relay_url", "") ?: "") }
        var chatRelayPort by remember { mutableStateOf(prefs?.getString("relay_port", "56789") ?: "56789") }
        var chatText by remember { mutableStateOf("") }
        var useRelay by remember { mutableStateOf(false) }
        var guestName by remember { mutableStateOf("Invitado") }
        val chatListState = rememberLazyListState()
        val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

        LaunchedEffect(chatMessages.size) {
            if (chatMessages.isNotEmpty()) chatListState.animateScrollToItem(chatMessages.size - 1)
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {

        // ── Passwords ──
        Text("Generador de contrasenas", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Button(onClick = { vm.generatePasswords() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(8.dp)); Text("Generar 4 contrasenas seguras")
        }
        passwords.forEach { (pw, bits) ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1a237e))
            ) {
                Text(pw, modifier = Modifier.padding(12.dp), fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("  ~${bits} bits de entropia",
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Report ──
        Text("Exportar reporte", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { saveReport(context, vm, "html") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("HTML", fontSize = 13.sp)
            }
            Button(
                onClick = { saveReport(context, vm, "csv") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("CSV", fontSize = 13.sp)
            }
            Button(
                onClick = { saveReport(context, vm, "json") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
            ) {
                Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("JSON", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Configuracion ──
        val settings by vm.settings.collectAsState()
        Text("Configuracion", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Notificaciones", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = settings.notificationsEnabled, onCheckedChange = { vm.toggleNotifications() })
                }
                Spacer(Modifier.height(8.dp))
                Text("Intervalo de escaneo: ${settings.scanIntervalSec}s", fontSize = 14.sp)
                Slider(
                    value = settings.scanIntervalSec.toFloat(),
                    onValueChange = { vm.setScanInterval(it.toInt()) },
                    valueRange = 5f..60f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("5s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text("60s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Privacidad ──
        Text("Privacidad", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://github.com/tuusuario/wifi-auditor-pro/blob/main/PRIVACY_POLICY.md")
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
        ) {
            Icon(Icons.Filled.Shield, null); Spacer(Modifier.width(8.dp)); Text("Politica de privacidad")
        }

        Spacer(Modifier.height(16.dp))

        // ── Monitor ──
        Text("Monitor continuo", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startMonitor() }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Iniciar")
            }
            Button(onClick = { vm.stopMonitor() }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFd32f2f))) {
                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Detener")
            }
        }
        monitorChanges.forEach { msg ->
            val color = if (msg.startsWith("+")) Color(0xFF4CAF50)
                       else if (msg.startsWith("-")) Color(0xFFf44336)
                       else Color.White
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(msg, modifier = Modifier.padding(8.dp), fontSize = 12.sp, color = color,
                    fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Chat TCP ──
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A5F))) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Chat, null, Modifier.size(20.dp), tint = Color(0xFF64B5F6))
                    Spacer(Modifier.width(6.dp))
                    Text("Chat TCP", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = useRelay,
                        onClick = {
                            useRelay = !useRelay
                            if (!useRelay) vm.disconnectRelay()
                            if (useRelay) vm.stopChatServer()
                        },
                        label = { Text(if (useRelay) "Remoto" else "Local", fontSize = 11.sp, color = Color.White) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = if (useRelay) Color(0xFF1565C0) else Color(0xFF388E3C),
                            selectedContainerColor = Color(0xFF1565C0)
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (!useRelay) {
                    // Modo Local
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (chatServerRunning) Color(0xFF4CAF50) else Color(0xFF757575), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(if (chatServerRunning) "Server: ${vm.chatLocalIp}" else "Server apagado",
                            fontSize = 11.sp, color = Color(0xFFB0BEC5))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (chatServerRunning) vm.stopChatServer() else vm.startChatServer() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (chatServerRunning) Color(0xFFd32f2f) else Color(0xFF388E3C))) {
                            Icon(if (chatServerRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text(if (chatServerRunning) "Detener server" else "Iniciar server", color = Color.White)
                        }
                        Button(onClick = { vm.clearChat() }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF546E7A))) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Limpiar", color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = chatTargetIp, onValueChange = { chatTargetIp = it; prefs?.edit()?.putString("target_ip", it)?.apply() },
                            label = { Text("IP destino") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Ej: 192.168.1.50") })
                } else {
                    // Modo Remoto
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (chatRelayConnected) Color(0xFF4CAF50) else Color(0xFF757575), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(if (chatRelayConnected) "Conectado al relay" else "Desconectado",
                            fontSize = 11.sp, color = Color(0xFFB0BEC5))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(value = chatRelayUrl, onValueChange = { chatRelayUrl = it; prefs?.edit()?.putString("relay_url", it)?.apply() },
                            modifier = Modifier.weight(1f), singleLine = true,
                            label = { Text("Servidor relay") },
                            placeholder = { Text("ej: mirelay.railway.app") })
                        OutlinedTextField(value = chatRelayPort, onValueChange = { chatRelayPort = it; prefs?.edit()?.putString("relay_port", it)?.apply() },
                            modifier = Modifier.width(80.dp), singleLine = true,
                            label = { Text("Puerto") })
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (chatRelayConnected) vm.disconnectRelay()
                            else vm.connectRelay(chatRelayUrl, chatRelayPort.toIntOrNull() ?: 56789)
                        }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (chatRelayConnected) Color(0xFFd32f2f) else Color(0xFF1565C0))) {
                            Icon(if (chatRelayConnected) Icons.Filled.Stop else Icons.Filled.Cloud, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text(if (chatRelayConnected) "Desconectar" else "Conectar relay", color = Color.White)
                        }
                        Button(onClick = { vm.clearChat() }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF546E7A))) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Limpiar", color = Color.White)
                        }
                    }
                    // Guest list when connected
                    if (chatRelayConnected) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = guestName, onValueChange = { guestName = it; vm.setGuestName(it) },
                                modifier = Modifier.weight(1f), singleLine = true,
                                label = { Text("Tu apodo") })
                        }
                        if (guestList.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Usuarios conectados:", fontSize = 11.sp, color = Color(0xFF90CAF9))
                            Spacer(Modifier.height(2.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                                items(guestList) { (id, name) ->
                                    TextButton(onClick = { vm.selectGuest(id) },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Filled.Person, null, Modifier.size(16.dp), tint = Color(0xFF4CAF50))
                                        Spacer(Modifier.width(4.dp))
                                        Text(name, fontSize = 13.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    } // fin scrollable settings

        // ── Mensajes del chat (estilo WhatsApp) ──
        Column(modifier = Modifier.weight(1f).fillMaxWidth().imePadding()) {
            if (chatMessages.isNotEmpty()) {
                LazyColumn(state = chatListState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
                    items(chatMessages) { msg ->
                        val isMe = msg.fromMe
                        val bg = if (isMe) Color(0xFF005C4B) else Color(0xFF202C33)
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp), contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
                            Box(modifier = Modifier.widthIn(max = 290.dp).background(bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                16.dp, 16.dp, if (isMe) 16.dp else 4.dp, if (isMe) 4.dp else 16.dp
                            )).padding(12.dp)) {
                                Text(msg.text, color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Sin mensajes", fontSize = 13.sp, color = Color(0xFF8696A0))
                }
            }
            // Input field siempre al fondo
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                OutlinedTextField(value = chatText, onValueChange = { chatText = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Mensaje...") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                        val target = if (useRelay) chatRelayUrl else chatTargetIp
                        if (target.isNotBlank() && chatText.isNotBlank()) {
                            vm.sendChatMessage(target, chatText); chatText = ""
                        }
                    }))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val target = if (useRelay) chatRelayUrl else chatTargetIp
                    vm.sendChatMessage(target, chatText); chatText = ""
                }, modifier = Modifier.height(56.dp)) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Filled.Send, null)
                }
            }
        }
    } // fin outer Column
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
