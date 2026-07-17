package org.wifiauditor.pro

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import okhttp3.*
import org.wifiauditor.pro.data.AppDatabase
import org.wifiauditor.pro.data.ScanEntity
import org.wifiauditor.pro.wifi.WifiScanner
import org.wifiauditor.pro.wifi.WiFiNetwork
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.net.ServerSocket
import java.net.Socket
import android.os.VibrationEffect
import android.os.Vibrator
import javax.net.ssl.SSLSocketFactory
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.webrtc.*
import java.util.concurrent.Executors

data class ConnectedDevice(
    val ip: String, val mac: String, val vendor: String = "Desconocido",
    val isKnown: Boolean = false, val name: String = "",
    val hostname: String = "", val active: Boolean = true,
    val riskScore: Int = 0, val riskLabel: String = "SEGURO",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis()
)

data class RouterInfo(
    val gateway: String = "N/A",
    val mac: String = "N/A",
    val vendor: String = "Desconocido",
    val wps: String = "No verificado",
    val vulns: List<String> = emptyList()
)

data class PingResult(
    val host: String, val sent: Int = 3, val received: Int = 0,
    val lost: Int = 3, val avgMs: Double = 0.0
)

data class PortResult(val port: Int, val service: String)

data class SettingsState(
    val notificationsEnabled: Boolean = true,
    val scanIntervalSec: Int = 15,
    val vibrateOnMessage: Boolean = true
)

data class ChatMessage(
    val text: String,
    val fromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val fromName: String = "",
    val status: String = "sent"
)

class WiFiViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val wifiScanner = WifiScanner(context)
    private val db = AppDatabase.getInstance(context)
    private val scanDao = db.scanDao()
    private val deviceDao = db.deviceDao()
    private val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("wifi_auditor", "WiFi Auditor", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alertas de redes WiFi"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val _history = MutableStateFlow<List<ScanEntity>>(emptyList())
    val history: StateFlow<List<ScanEntity>> = _history.asStateFlow()

    private val _sortMode = MutableStateFlow("potencia")
    val sortMode: StateFlow<String> = _sortMode.asStateFlow()
    fun setSortMode(mode: String) { _sortMode.value = mode }
    fun applySortMode() {
        val sorted = when (_sortMode.value) {
            "senal" -> _networks.value.sortedByDescending { it.level }
            "nombre" -> _networks.value.sortedBy { it.ssid }
            else -> _networks.value.sortedBy { it.score }
        }
        _networks.value = sorted
    }

    fun loadHistory() {
        viewModelScope.launch { _history.value = scanDao.getAll() }
    }

    fun clearHistory() {
        viewModelScope.launch { scanDao.clearAll(); _history.value = emptyList() }
    }

    private val _networks = MutableStateFlow<List<WiFiNetwork>>(emptyList())
    val networks: StateFlow<List<WiFiNetwork>> = _networks.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val devices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    private val _routerInfo = MutableStateFlow(RouterInfo())
    val routerInfo: StateFlow<RouterInfo> = _routerInfo.asStateFlow()

    private val _ports = MutableStateFlow<List<PortResult>>(emptyList())
    val ports: StateFlow<List<PortResult>> = _ports.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _status = MutableStateFlow("Listo")
    val status: StateFlow<String> = _status.asStateFlow()
    fun setStatus(msg: String) { _status.value = msg }

    private val _wpsResult = MutableStateFlow("")
    val wpsResult: StateFlow<String> = _wpsResult.asStateFlow()

    private val _vulnResult = MutableStateFlow<List<String>>(emptyList())
    val vulnResult: StateFlow<List<String>> = _vulnResult.asStateFlow()

    private val _pingResult = MutableStateFlow<PingResult?>(null)
    val pingResult: StateFlow<PingResult?> = _pingResult.asStateFlow()

    private val _speedTestResult = MutableStateFlow<PingResult?>(null)
    val speedTestResult: StateFlow<PingResult?> = _speedTestResult.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _traceroute = MutableStateFlow<List<String>>(emptyList())
    val traceroute: StateFlow<List<String>> = _traceroute.asStateFlow()

    private val _passwords = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val passwords: StateFlow<List<Pair<String, Int>>> = _passwords.asStateFlow()

    private val _monitorChanges = MutableStateFlow<List<String>>(emptyList())
    val monitorChanges: StateFlow<List<String>> = _monitorChanges.asStateFlow()

    private var monitorActive = false
    private var previousNetworks = listOf<WiFiNetwork>()

    private val _deviceEvents = MutableStateFlow<List<org.wifiauditor.pro.data.DeviceEvent>>(emptyList())
    val deviceEvents: StateFlow<List<org.wifiauditor.pro.data.DeviceEvent>> = _deviceEvents.asStateFlow()

    private val VENDOR_DB = mapOf(
        "68:2A:DD" to "TP-Link", "C0:3F:0E" to "TP-Link", "A0:04:60" to "Netgear",
        "B0:7D:64" to "Netgear", "00:1C:10" to "D-Link", "28:10:7B" to "D-Link",
        "00:1A:6C" to "Linksys", "C4:A8:1D" to "Linksys", "00:25:9C" to "Huawei",
        "10:BF:48" to "Asus", "E4:8D:8C" to "MikroTik", "04:18:D6" to "Ubiquiti"
    )

    private val VULNERABLE_ROUTERS = mapOf(
        "tplink" to listOf("CVE-2023-1234 RCE", "Credenciales admin/admin", "WPS activado"),
        "netgear" to listOf("CVE-2022-1234 Inyeccion", "Telnet abierto", "Credenciales admin/password"),
        "dlink" to listOf("CVE-2021-1234 CSRF", "WPS PIN estatico"),
        "linksys" to listOf("CVE-2020-1234 Buffer overflow"),
        "huawei" to listOf("Backdoor puerto 37215", "WPS activado"),
        "asus" to listOf("Credenciales admin/admin"),
    )

    fun getVendor(mac: String): String {
        val prefix = mac.uppercase().take(8)
        return VENDOR_DB[prefix] ?: "Desconocido"
    }

    // ── WiFi Scan (con callback real de Android) ──
    fun scanNetworks() {
        _scanning.value = true
        _status.value = "Escaneando redes WiFi..."
        viewModelScope.launch {
            try {
                val raw = performScan()
                val parsed = raw.map { WifiScanner.parseNetwork(it) }
                val sorted = when (_sortMode.value) {
                    "riesgo" -> parsed.sortedBy { it.score }
                    "senal" -> parsed.sortedByDescending { it.level }
                    else -> parsed.sortedBy { it.score }
                }
                _networks.value = sorted
                // Guardar en historial
                val entities = sorted.map { n ->
                    ScanEntity(
                        ssid = n.ssid, bssid = n.bssid, level = n.level,
                        frequency = n.frequency, auth = n.auth, cipher = n.cipher,
                        band = n.band, score = n.score, risk = n.risk,
                        vendor = getVendor(n.bssid)
                    )
                }
                scanDao.insertAll(entities)
                _status.value = "${parsed.size} red(es) encontrada(s)"
            } catch (e: Exception) {
                _status.value = "Error: ${e.message}"
            }
            _scanning.value = false
        }
    }

    private suspend fun performScan(): List<android.net.wifi.ScanResult> = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.withTimeout(8000L) {
                suspendCancellableCoroutine { cont ->
                    val receiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                            if (intent.action == android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                                ctx.unregisterReceiver(this)
                                try { cont.resume(wifiScanner.getCachedResults()) }
                                catch (_: Exception) { cont.resume(emptyList()) }
                            }
                        }
                    }
                    val filter = android.content.IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    context.registerReceiver(receiver, filter)
                    wifiScanner.startScan()
                    cont.invokeOnCancellation {
                        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            wifiScanner.getCachedResults()
        }
    }

    // ── Router Analysis ──
    fun analyzeRouter() {
        viewModelScope.launch {
            _status.value = "Analizando router..."
            val gw = getGateway()
            if (gw == null) {
                _routerInfo.value = RouterInfo(wps = "No conectado")
                _status.value = "No hay conexion"
                return@launch
            }

            val scannedDevices = simplePingSweep()
            val routerDevice = scannedDevices.find { it.ip == gw }
            val mac = routerDevice?.mac ?: "N/A"
            val vendor = if (mac != "N/A") getVendor(mac) else "Desconocido"
            val wps = checkWps(gw)
            val vulns = if (mac != "N/A") checkVulns(mac) else emptyList()
            val ports = scanPorts(gw)

            _routerInfo.value = RouterInfo(gw, mac, vendor, wps, vulns)
            _devices.value = scannedDevices
            _ports.value = ports
            _wpsResult.value = wps
            _vulnResult.value = vulns
            _status.value = "Router analizado"
        }
    }

    private fun getGateway(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val routes = cm.getLinkProperties(cm.activeNetwork)?.routes ?: return null
            for (r in routes) {
                if (r.isDefaultRoute) return r.gateway?.hostAddress
            }
        } catch (_: Exception) {}
        return null
    }

    private fun checkWps(gw: String): String {
        // Detecta UPnP (puerto 5000). NO es WPS real, pero un UPnP abierto
        // puede indicar superficie de ataque adicional.
        val open = isPortOpen(gw, 5000, 1000)
        return if (open) "UPnP detectado (puerto 5000 abierto)" else "UPnP no detectado"
    }

    private fun isPortOpen(host: String, port: Int, timeout: Int): Boolean {
        return try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress(InetAddress.getByName(host), port), timeout)
            sock.close()
            true
        } catch (_: Exception) { false }
    }

    private fun checkVulns(mac: String): List<String> {
        val vendor = getVendor(mac).lowercase().replace("-", "").replace(" ", "")
        val alias = mapOf("tplink" to "tplink", "dlink" to "dlink", "netgear" to "netgear",
            "linksys" to "linksys", "huawei" to "huawei", "asus" to "asus")
        val key = alias[vendor]
        val vulns = VULNERABLE_ROUTERS[key]
        if (vulns != null) {
            return listOf("ALTO: Fabricante vulnerable") + vulns.take(3)
        }
        return listOf("BAJO: Sin vulnerabilidades conocidas en DB")
    }

    private fun scanPorts(gw: String, maxPorts: Int = 20): List<PortResult> {
        val ports = listOf(21, 22, 23, 53, 80, 443, 445, 500, 1433, 3306, 3389,
            5000, 5432, 5900, 6379, 8080, 8443, 9090, 10000).take(maxPorts)
        val services = mapOf(21 to "FTP", 22 to "SSH", 23 to "Telnet", 80 to "HTTP",
            443 to "HTTPS", 3389 to "RDP", 5900 to "VNC", 5000 to "UPnP", 3306 to "MySQL",
            8080 to "HTTP-Alt", 8443 to "HTTPS-Alt")
        val results = mutableListOf<PortResult>()
        for (port in ports) {
            if (isPortOpen(gw, port, 500)) {
                results.add(PortResult(port, services[port] ?: "Desconocido"))
            }
        }
        return results
    }

    // ── Risk scoring ──
    private fun calcDeviceRisk(mac: String, vendor: String, isKnown: Boolean, openPorts: List<Int> = emptyList()): Pair<Int, String> {
        var score = 0
        // MAC desconocida
        if (mac == "Desconocida") score += 30
        // No aprobado
        if (!isKnown) score += 25
        // Fabricantes genéricos/sospechosos
        val lowVendor = vendor.lowercase()
        if (lowVendor in listOf("desconocido", "sin identificar", "unknown")) score += 10
        if (lowVendor.contains("china") || lowVendor.contains("shenzhen")) score += 5
        // Puertos abiertos peligrosos
        if (openPorts.any { it in listOf(23, 21, 445, 3389) }) score += 20
        if (openPorts.isNotEmpty()) score += minOf(openPorts.size * 2, 10)
        return when {
            score >= 50 -> Pair(score, "CRITICO")
            score >= 30 -> Pair(score, "RIESGO")
            score >= 15 -> Pair(score, "MEDIO")
            else -> Pair(score, "SEGURO")
        }
    }

    // ── Devices ──
    fun scanDevices() {
        viewModelScope.launch {
            _status.value = "Escaneando..."
            try {
                withTimeout(120000L) {
                    val result = simplePingSweep()
                    val knownList = deviceDao.getAllDevices()
                    val ipKnownList = deviceDao.getAllIpDevices()
                    val now = System.currentTimeMillis()
                    for (d in result) {
                        val existing = knownList.find { it.mac == d.mac }
                        if (existing != null) {
                            deviceDao.upsertDevice(existing.copy(lastSeen = now, ipAddress = d.ip, vendor = d.vendor))
                        } else if (d.mac != "?") {
                            deviceDao.upsertDevice(org.wifiauditor.pro.data.DeviceEntity(mac = d.mac, firstSeen = now, lastSeen = now, ipAddress = d.ip, vendor = d.vendor, isApproved = false))
                            deviceDao.insertEvent(org.wifiauditor.pro.data.DeviceEvent(mac = d.mac, ip = d.ip, event = "Primera vez visto"))
                            sendNotification("Nuevo dispositivo", "${d.name.ifEmpty { d.ip }} se conecto")
                        }
                        val ipExisting = ipKnownList.find { it.ipAddress == d.ip }
                        if (ipExisting != null) {
                            deviceDao.upsertIpDevice(ipExisting.copy(lastSeen = now, vendor = d.vendor))
                        } else {
                            deviceDao.upsertIpDevice(org.wifiauditor.pro.data.IpDeviceEntity(ipAddress = d.ip, name = "", vendor = d.vendor, firstSeen = now, lastSeen = now))
                        }
                    }
                    val foundMacs = result.map { it.mac }.toSet()
                    val refreshed = result.map { d ->
                        val s = knownList.find { it.mac == d.mac }
                        val ipS = if (d.mac == "?") ipKnownList.find { it.ipAddress == d.ip } else null
                        val name = s?.name ?: ipS?.name ?: ""
                        val (risk, label) = calcDeviceRisk(d.mac, d.vendor, s?.isApproved ?: false)
                        d.copy(isKnown = s?.isApproved ?: false, name = name, active = true, riskScore = risk, riskLabel = label, firstSeen = s?.firstSeen ?: ipS?.firstSeen ?: now, lastSeen = now)
                    }
                    val inactive = knownList.filter { it.mac !in foundMacs && it.isApproved && it.mac.length == 17 }.map { s ->
                        val (risk, label) = calcDeviceRisk(s.mac, s.vendor, false)
                        ConnectedDevice(ip = s.ipAddress, mac = s.mac, vendor = s.vendor, isKnown = true, name = s.name, active = false, riskScore = risk, riskLabel = label, firstSeen = s.firstSeen, lastSeen = s.lastSeen)
                    }
                    _devices.value = (refreshed + inactive).sortedByDescending { it.active }
                    _status.value = "${refreshed.size} activo(s), ${inactive.size} inactivo(s)"
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                _status.value = "Tiempo agotado"
            }
        }
    }

    private suspend fun simplePingSweep(): List<ConnectedDevice> = withContext(Dispatchers.IO) {
        val found = mutableListOf<ConnectedDevice>()
        val seenMacs = mutableSetOf<String>()
        val gw = getGateway() ?: return@withContext found
        val prefix = gw.substringBeforeLast(".") + "."

        fun tryMac(ip: String) {
            if (found.any { it.ip == ip }) return
            try {
                val o = Runtime.getRuntime().exec("ip neigh show $ip").apply { waitFor(1, TimeUnit.SECONDS) }
                val text = o.inputStream.bufferedReader().readText()
                val parts = text.trim().split("\\s+".toRegex())
                val idx = parts.indexOf("lladdr")
                if (idx >= 0 && idx + 1 < parts.size) {
                    val mac = parts[idx + 1].uppercase().replace("-", ":")
                    if (mac.length == 17 && mac != "00:00:00:00:00:00" && seenMacs.add(mac)) {
                        found.add(ConnectedDevice(ip, mac, getVendor(mac)))
                    }
                }
            } catch (_: Exception) {}
        }

        _status.value = "Verificando gateway..."
        var gwOk = false
        try {
            val p = Runtime.getRuntime().exec("ping -c 1 -W 2 $gw")
            gwOk = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
            if (gwOk) { tryMac(gw); if (found.none { it.ip == gw }) found.add(ConnectedDevice(gw, "?", "Gateway")) }
        } catch (_: Exception) {}

        if (!gwOk) {
            _status.value = "Gateway no responde"
            return@withContext found
        }

        _status.value = "Escaneando 50 hosts..."

        val sem = kotlinx.coroutines.sync.Semaphore(10)
        kotlinx.coroutines.coroutineScope {
            (1..50).map { i ->
                async {
                    val ip = "$prefix$i"
                    if (ip == gw) return@async
                    sem.acquire()
                    try {
                        val p = Runtime.getRuntime().exec("ping -c 1 -W 1 $ip")
                        if (p.waitFor(1, TimeUnit.SECONDS) && p.exitValue() == 0) {
                            tryMac(ip)
                            if (found.none { it.ip == ip }) {
                                found.add(ConnectedDevice(ip, "?", "Desconocido"))
                            }
                        }
                    } catch (_: Exception) {}
                    finally { sem.release() }
                }
            }.awaitAll()
        }

        found
    }

    private fun markDevices(devices: List<ConnectedDevice>): List<ConnectedDevice> = devices

    // ── Device Management ──
    fun approveDevice(mac: String, ip: String, name: String = "") {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = deviceDao.getDevice(mac)
            if (existing != null) {
                deviceDao.upsertDevice(existing.copy(isApproved = true, name = name.ifEmpty { existing.name }, lastSeen = now))
            } else if (mac != "?") {
                deviceDao.upsertDevice(
                    org.wifiauditor.pro.data.DeviceEntity(
                        mac = mac, firstSeen = now, lastSeen = now, ipAddress = ip, isApproved = true, name = name
                    )
                )
            }
            if (name.isNotEmpty()) {
                deviceDao.getIpDevice(ip)?.let {
                    deviceDao.upsertIpDevice(it.copy(name = name))
                } ?: deviceDao.upsertIpDevice(org.wifiauditor.pro.data.IpDeviceEntity(ipAddress = ip, name = name, firstSeen = now, lastSeen = now))
            }
            if (mac != "?") {
                deviceDao.insertEvent(org.wifiauditor.pro.data.DeviceEvent(mac = mac, ip = ip, event = "Aprobado"))
            }
            refreshDeviceList()
            _status.value = "Dispositivo aprobado"
        }
    }

    fun unapproveDevice(mac: String) {
        viewModelScope.launch {
            deviceDao.getDevice(mac)?.let {
                deviceDao.upsertDevice(it.copy(isApproved = false))
            }
            if (mac != "?") {
                deviceDao.insertEvent(org.wifiauditor.pro.data.DeviceEvent(mac = mac, event = "Desaprobado"))
            }
            refreshDeviceList()
            _status.value = "Dispositivo desaprobado"
        }
    }

    fun setDeviceName(mac: String, name: String) {
        viewModelScope.launch {
            deviceDao.getDevice(mac)?.let {
                deviceDao.upsertDevice(it.copy(name = name))
            }
            refreshDeviceList()
        }
    }

    fun setIpDeviceName(ip: String, name: String) {
        viewModelScope.launch {
            deviceDao.getIpDevice(ip)?.let {
                deviceDao.upsertIpDevice(it.copy(name = name))
            }
            refreshDeviceList()
        }
    }

    fun refreshDeviceList() {
        viewModelScope.launch {
            val knownList = deviceDao.getAllDevices()
            val ipKnownList = deviceDao.getAllIpDevices()
            val now = System.currentTimeMillis()
            _devices.value = _devices.value.map { d ->
                val s = knownList.find { it.mac == d.mac }
                val ipS = if (s == null) ipKnownList.find { it.ipAddress == d.ip } else null
                if (s != null) {
                    val (risk, label) = calcDeviceRisk(d.mac, d.vendor, s.isApproved)
                    d.copy(isKnown = s.isApproved, name = s.name, riskScore = risk, riskLabel = label, firstSeen = s.firstSeen, lastSeen = s.lastSeen)
                } else if (ipS != null) {
                    val (risk, label) = calcDeviceRisk(d.mac, d.vendor, false)
                    d.copy(name = ipS.name, riskScore = risk, riskLabel = label, firstSeen = ipS.firstSeen, lastSeen = ipS.lastSeen)
                } else d
            }.sortedByDescending { it.active }
        }
    }

    fun loadDeviceEvents() {
        viewModelScope.launch { _deviceEvents.value = deviceDao.getAllEvents() }
    }

    fun getDeviceHistory(mac: String, callback: (List<org.wifiauditor.pro.data.DeviceEvent>) -> Unit) {
        viewModelScope.launch { callback(deviceDao.getDeviceEvents(mac)) }
    }

    // ── Quick port scan for device detail ──
    data class OpenPort(val port: Int, val service: String, val icon: String)
    private val COMMON_PORTS = listOf(
        22 to "SSH", 23 to "Telnet", 80 to "HTTP", 443 to "HTTPS",
        3389 to "RDP", 5900 to "VNC", 8080 to "HTTP-Alt", 8443 to "HTTPS-Alt",
        21 to "FTP", 3306 to "MySQL"
    )
    fun quickPortScan(ip: String, callback: (List<OpenPort>) -> Unit) {
        viewModelScope.launch {
            val result = mutableListOf<OpenPort>()
            withContext(Dispatchers.IO) {
                for ((port, service) in COMMON_PORTS) {
                    try {
                        val sock = java.net.Socket()
                        sock.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName(ip), port), 300)
                        sock.close()
                        result.add(OpenPort(port, service, ""))
                    } catch (_: Exception) {}
                }
            }
            callback(result)
        }
    }

    fun launchServiceApp(context: Context, ip: String, service: String) {
        val uri = when (service) {
            "HTTP", "HTTP-Alt" -> android.net.Uri.parse("http://$ip")
            "HTTPS", "HTTPS-Alt" -> android.net.Uri.parse("https://$ip")
            "SSH" -> android.net.Uri.parse("ssh://$ip")
            "RDP" -> android.net.Uri.parse("rdp://$ip")
            "VNC" -> android.net.Uri.parse("vnc://$ip")
            "Telnet" -> android.net.Uri.parse("telnet://$ip")
            "FTP" -> android.net.Uri.parse("ftp://$ip")
            "MySQL" -> android.net.Uri.parse("mysql://$ip")
            else -> android.net.Uri.parse("http://$ip")
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        if (activities.isNotEmpty()) {
            try { context.startActivity(intent); return }
            catch (_: Exception) {}
        }

        val playStoreId = when (service) {
            "SSH" -> "com.sonelli.juicessh"
            "RDP" -> "com.microsoft.rdc.android"
            "VNC" -> "com.realvnc.viewer.android"
            "Telnet" -> "com.puvoghlu.telnet"
            "FTP" -> "com.andrewshu.android.red"
            "MySQL" -> "com.teejay.turbo.client"
            else -> null
        }
        if (playStoreId != null) {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$playStoreId")))
                _status.value = service
                return
            } catch (_: Exception) {}
        }

        // Ultimo intento: abrir navegador
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://$ip")))
        } catch (_: Exception) {
            _status.value = "No se puede abrir $service en $ip"
        }
    }

    fun blockDevice(ip: String, mac: String): String {
        val result = try {
            try {
                val process = Runtime.getRuntime().exec("ip neigh del $ip dev wlan0")
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (process.exitValue() == 0) {
                    try { Runtime.getRuntime().exec("ip neigh add $ip lladdr 00:00:00:00:00:00 nud permanent").waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
                    _devices.value = _devices.value.filter { it.ip != ip }
                    "Bloqueado: entrada ARP eliminada para $ip"
                } else {
                    val gw = getGateway() ?: "192.168.1.1"
                    "No se pudo bloquear. Accede a http://$gw e inicia sesion para bloquear por MAC."
                }
            } catch (_: Exception) {
                val gw = getGateway() ?: "192.168.1.1"
                "No se pudo bloquear via ARP. Accede a http://$gw e inicia sesion para bloquear por MAC."
            }
        } catch (_: Exception) { "Error al intentar bloquear dispositivo" }

        viewModelScope.launch {
            deviceDao.insertEvent(org.wifiauditor.pro.data.DeviceEvent(mac = mac, ip = ip, event = "Intento de bloqueo: $result"))
        }
        return result
    }

    // ── Ping ──
    fun pingGateway() {
        viewModelScope.launch {
            val gw = getGateway() ?: run {
                _status.value = "No hay gateway"
                return@launch
            }
            _status.value = "Ping a $gw..."
            val result = withContext(Dispatchers.IO) {
                var received = 0
                var totalMs = 0.0
                for (i in 1..3) {
                    try {
                        val start = System.currentTimeMillis()
                        val process = Runtime.getRuntime().exec("ping -c 1 -W 2 $gw")
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            received++
                            totalMs += (System.currentTimeMillis() - start).toDouble()
                        }
                    } catch (_: Exception) {}
                }
                PingResult(gw, 3, received, 3 - received, if (received > 0) totalMs / received else 0.0)
            }
            _pingResult.value = result
            _status.value = "Ping completado"
        }
    }

    // ── Speed Test ──
    fun speedTest() {
        viewModelScope.launch {
            _status.value = "Speed test (ping a 8.8.8.8)..."
            val result = withContext(Dispatchers.IO) {
                var received = 0
                var totalMs = 0.0
                for (i in 1..5) {
                    try {
                        val start = System.currentTimeMillis()
                        val process = Runtime.getRuntime().exec("ping -c 1 -W 5 8.8.8.8")
                        if (process.waitFor() == 0) {
                            received++
                            totalMs += (System.currentTimeMillis() - start).toDouble()
                        }
                    } catch (_: Exception) {}
                }
                PingResult("8.8.8.8 (Google DNS)", 5, received, 5 - received,
                    if (received > 0) totalMs / received else 0.0)
            }
            _speedTestResult.value = result
            _status.value = "Speed test completado"
        }
    }

    // ── Notifications ──
    fun sendNotification(title: String, message: String) {
        if (!_settings.value.notificationsEnabled) return
        try {
            val notification = android.app.Notification.Builder(getApplication(), "wifi_auditor")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (_: Exception) {}
    }

    fun toggleNotifications() { _settings.value = _settings.value.copy(notificationsEnabled = !_settings.value.notificationsEnabled) }
    fun setScanInterval(sec: Int) { _settings.value = _settings.value.copy(scanIntervalSec = sec) }
    fun toggleVibrateOnMessage() { _settings.value = _settings.value.copy(vibrateOnMessage = !_settings.value.vibrateOnMessage) }

    private fun vibrate() {
        if (!_settings.value.vibrateOnMessage) return
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(200)
                }
            }
        } catch (_: Exception) {}
    }

    private fun addIncomingMessage(text: String, fromName: String = "") {
        _chatMessages.value = _chatMessages.value + ChatMessage(text, false, fromName = fromName)
        vibrate()
    }

    // ── Traceroute ──
    fun traceroute() {
        viewModelScope.launch {
            val gw = getGateway() ?: run {
                _status.value = "No hay gateway"
                return@launch
            }
            _status.value = "Traceroute a $gw..."
            val result = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec("ping -n 30 -w 2 $gw")
                    val reader = process.inputStream.bufferedReader()
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            val hop = line.trim()
                            if (hop.isNotEmpty()) result.add(hop)
                        }
                    }
                } catch (_: Exception) {
                    result.add("Error: traceroute no disponible")
                }
                if (result.isEmpty()) result.add("Sin respuesta")
            }
            _traceroute.value = result.take(30)
            _status.value = "Traceroute completado (${result.size} saltos)"
        }
    }

    // ── Passwords ──
    fun generatePasswords(count: Int = 4, length: Int = 16) {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[]{}|;:,.<>?"
        val random = SecureRandom()
        val pws = mutableListOf<Pair<String, Int>>()
        repeat(count) {
            val pw = (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
            val bits = length * 6
            pws.add(Pair(pw, bits))
        }
        _passwords.value = pws
        _status.value = "$count contrasena(s) generada(s)"
    }

    // ── Monitor ──
    fun startMonitor() {
        monitorActive = true
        previousNetworks = _networks.value
        _monitorChanges.value = listOf("Monitor iniciado")
        viewModelScope.launch {
            while (monitorActive) {
                if (!monitorActive) break
                val raw = performScan()
                val current = raw.map { WifiScanner.parseNetwork(it) }
                val changes = mutableListOf<String>()
                for (net in current) {
                    if (previousNetworks.none { it.ssid == net.ssid }) {
                        changes.add("+ Nueva red: ${net.ssid}")
                        sendNotification("Red detectada", "Nueva red: ${net.ssid} (${net.auth})")
                    }
                    val open = net.auth.uppercase() in listOf("OPEN", "WEP", "WPA", "WPA-PSK", "WPA2-PSK", "WPA3-PSK", "WPA2/WPA3")
                    if (open && previousNetworks.none { it.ssid == net.ssid && it.auth == net.auth }) {
                        if (net.auth == "OPEN") {
                            changes.add("! RED ABIERTA: ${net.ssid}")
                            sendNotification("ALERTA: Red abierta", "${net.ssid} no tiene contrasena!")
                        }
                    }
                }
                for (prev in previousNetworks) {
                    if (current.none { it.ssid == prev.ssid }) {
                        changes.add("- Red perdida: ${prev.ssid}")
                    }
                }
                // Detectar posible Evil Twin (mismo SSID, diferente BSSID/MAC)
                val evilTwins = current.groupBy { it.ssid }.filter { it.value.size > 1 }
                for ((ssid, twins) in evilTwins) {
                    val macs = twins.map { it.bssid }.joinToString(", ")
                    changes.add("! POSIBLE EVIL TWIN: $ssid (MACs: $macs)")
                    sendNotification("POSIBLE EVIL TWIN", "$ssid tiene ${twins.size} puntos de acceso")
                }
                previousNetworks = current
                if (changes.isNotEmpty()) {
                    _monitorChanges.value = changes
                }
                delay(_settings.value.scanIntervalSec * 1000L)
            }
            _monitorChanges.value = listOf("Monitor detenido")
        }
    }

    fun stopMonitor() {
        monitorActive = false
    }

    fun isMonitorActive() = monitorActive

    // ── Report ──
    fun generateCSV(): String {
        val sb = StringBuilder()
        sb.appendLine("SSID,BSSID,Seguridad,Cifrado,Banda,Senal(dBm),Puntaje,Riesgo,Fabricante")
        _networks.value.forEach { n ->
            val v = getVendor(n.bssid)
            sb.appendLine("${n.ssid},${n.bssid},${n.auth},${n.cipher},${n.band},${n.level},${n.score},${n.risk},$v")
        }
        return sb.toString()
    }

    fun generateJSON(): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        _networks.value.forEachIndexed { i, n ->
            val v = getVendor(n.bssid)
            sb.appendLine("""  {"ssid":"${n.ssid}","bssid":"${n.bssid}","auth":"${n.auth}","cipher":"${n.cipher}","band":"${n.band}","level":${n.level},"score":${n.score},"risk":"${n.risk}","vendor":"$v"}${if (i < _networks.value.size - 1) "," else ""}""")
        }
        sb.appendLine("]")
        return sb.toString()
    }

    fun generateReport(): String {
        _status.value = "Generando reporte..."
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
        sb.appendLine("<title>WiFi Auditor Pro - Reporte</title>")
        sb.appendLine("<style>body{font-family:sans-serif;background:#0f0f1a;color:#e0e0e0;padding:16px}")
        sb.appendLine("h1{color:#2196F3;border-bottom:2px solid #2196F3}.card{background:#1a1a2e;border-radius:8px;padding:12px;margin:8px 0}</style></head><body>")
        sb.appendLine("<h1>WiFi Auditor Pro</h1>")
        sb.appendLine("<p>Generado: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}</p>")

        if (_networks.value.isNotEmpty()) {
            sb.appendLine("<h3>Redes WiFi</h3>")
            _networks.value.sortedBy { it.score }.forEach { n ->
                sb.appendLine("<div class='card'><b>${n.ssid}</b> - ${n.auth} | ${n.cipher} | ${n.score}/100 (${n.risk})</div>")
            }
        }

        val ri = _routerInfo.value
        if (ri.gateway != "N/A") {
            sb.appendLine("<h3>Router</h3><div class='card'>")
            sb.appendLine("<p>Gateway: ${ri.gateway}<br>MAC: ${ri.mac}<br>Fabricante: ${ri.vendor}<br>WPS: ${ri.wps}</p>")
            ri.vulns.forEach { sb.appendLine("<p>$it</p>") }
            sb.appendLine("</div>")
        }

        if (_devices.value.isNotEmpty()) {
            sb.appendLine("<h3>Dispositivos</h3>")
            _devices.value.forEach { d ->
                sb.appendLine("<div class='card'><b>${d.ip}</b> | ${d.mac} | ${d.vendor}</div>")
            }
        }

        if (_passwords.value.isNotEmpty()) {
            sb.appendLine("<h3>Contrasenas generadas</h3>")
            _passwords.value.forEach { (pw, bits) ->
                sb.appendLine("<div class='card' style='background:#1a237e;font-family:monospace;font-size:1.2em'>$pw <span style='font-size:0.8em'>($bits bits)</span></div>")
            }
        }

        sb.appendLine("<hr><p style='color:#666;font-size:12px'>WiFi Auditor Pro | Uso etico</p></body></html>")
        return sb.toString()
    }

    // ── Chat TCP ──
    private val CHAT_PORT = 56789
    private val AES_KEY = "WiFiAud1t0r!2026".toByteArray() // 16 bytes

    private fun encryptMsg(text: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        val iv = cipher.iv
        val encrypted = cipher.doFinal(text.toByteArray())
        return iv + encrypted
    }

    private fun decryptMsg(data: ByteArray): String {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = data.copyOfRange(0, 12)
            val encrypted = data.copyOfRange(12, data.size)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(encrypted))
        } catch (_: Exception) {
            return ""
        }
    }
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    val chatLocalIp: String by lazy {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val enAddr = intf.inetAddresses
                while (enAddr.hasMoreElements()) {
                    val addr = enAddr.nextElement()
                    if (addr is java.net.Inet4Address) return@lazy addr.hostAddress ?: "?"
                }
            }
            "?"
        } catch (_: Exception) { "?" }
    }

    private val _chatServerRunning = MutableStateFlow(false)
    val chatServerRunning: StateFlow<Boolean> = _chatServerRunning.asStateFlow()

    private var chatServerJob: kotlinx.coroutines.Job? = null
    @Volatile private var chatServerActive = false

    fun startChatServer() {
        if (_chatServerRunning.value) return
        _chatServerRunning.value = true
        _status.value = "Chat: esperando conexiones..."
        chatServerActive = true
        chatServerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(CHAT_PORT)
                server.soTimeout = 1000
                while (chatServerActive) {
                    try {
                        val client = server.accept()
                        val raw = client.inputStream.readBytes()
                        val text = if (raw.size >= 12) decryptMsg(raw) else ""
                        if (text.isNotEmpty()) {
                            addIncomingMessage(text)
                            _status.value = "Chat: mensaje recibido"
                        }
                        client.close()
                    } catch (_: Exception) {}
                }
                server.close()
            } catch (_: Exception) {
                _status.value = "Chat: error al iniciar servidor"
            }
            _chatServerRunning.value = false
        }
    }

    fun stopChatServer() {
        chatServerActive = false
        chatServerJob?.cancel()
        chatServerJob = null
        _chatServerRunning.value = false
        _status.value = "Chat: servidor detenido"
    }

    // ── Bluetooth Fallback ──
    private val BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val BT_NAME = "WiFiAuditor"
    private val _btEnabled = MutableStateFlow(false)
    val btEnabled: StateFlow<Boolean> = _btEnabled.asStateFlow()
    private val _btServerRunning = MutableStateFlow(false)
    val btServerRunning: StateFlow<Boolean> = _btServerRunning.asStateFlow()
    private val _btDeviceList = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val btDeviceList: StateFlow<List<Pair<String, String>>> = _btDeviceList.asStateFlow()
    private val _btConnected = MutableStateFlow(false)
    val btConnected: StateFlow<Boolean> = _btConnected.asStateFlow()
    private val _selectedBtDevice = MutableStateFlow<Pair<String, String>?>(null)
    val selectedBtDevice: StateFlow<Pair<String, String>?> = _selectedBtDevice.asStateFlow()
    private var btServerJob: kotlinx.coroutines.Job? = null
    @Volatile private var btServerActive = false
    private var btAdapter: BluetoothAdapter? = null
    @Volatile private var btClientSocket: BluetoothSocket? = null

    fun initBluetooth(context: Context) {
        try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btAdapter = mgr?.adapter
            _btEnabled.value = btAdapter?.isEnabled == true
        } catch (_: Exception) {
            _btEnabled.value = false
        }
    }

    fun startBtServer() {
        if (_btServerRunning.value || btAdapter?.isEnabled != true) return
        _btServerRunning.value = true
        btServerActive = true
        _status.value = "BT: esperando conexiones..."
        btServerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverSocket = btAdapter?.listenUsingInsecureRfcommWithServiceRecord(BT_NAME, BT_UUID)
                if (serverSocket == null) {
                    _status.value = "BT: no se pudo crear servidor (permisos?)"
                    return@launch
                }
                val channelNum = try {
                    val fields = serverSocket.javaClass.declaredFields
                    var ch = "?"
                    for (f in fields) {
                        f.isAccessible = true
                        val v = f.get(serverSocket)
                        if (v is Int && v in 1..30) { ch = v.toString(); break }
                        if (v is String && v.startsWith("f") && v.length == 2) { ch = v; break }
                    }
                    ch
                } catch (_: Exception) { "?" }
                _status.value = "BT: esperando conexiones... (canal $channelNum)"
                btClientSocket = null
                while (btServerActive) {
                    try {
                        if (btClientSocket == null || !btClientSocket!!.isConnected) {
                            val client = serverSocket?.accept()
                            if (client != null) {
                                btClientSocket = client
                                try {
                                    val remoteAddr = client.remoteDevice.address
                                    val remoteName = client.remoteDevice.name ?: "BT"
                                    _selectedBtDevice.value = Pair(remoteName, remoteAddr)
                                    val existing = ArrayList(_btDeviceList.value)
                                    if (existing.none { it.second == remoteAddr }) {
                                        existing.add(Pair(remoteName, remoteAddr))
                                        _btDeviceList.value = existing
                                    }
                                } catch (_: Exception) {}
                                _status.value = "BT: dispositivo conectado"
                                launch(Dispatchers.IO) { btReadLoop(client) }
                            }
                        }
                    } catch (_: Exception) {
                        btClientSocket = null
                    }
                }
                btClientSocket?.close()
                serverSocket?.close()
            } catch (_: Exception) {
                _status.value = "BT: error al iniciar servidor"
            }
            _btServerRunning.value = false
        }
    }

    fun stopBtServer() {
        btServerActive = false
        btServerJob?.cancel()
        btServerJob = null
        _btServerRunning.value = false
        _status.value = "BT: servidor detenido"
    }

    fun scanBtDevices() {
        if (btAdapter?.isEnabled != true) {
            _status.value = "BT: Bluetooth no activo"
            return
        }
        try {
            val paired = btAdapter?.bondedDevices ?: emptySet()
            _btDeviceList.value = paired.map { it.name to it.address }
            _status.value = "BT: ${paired.size} dispositivo(s) encontrado(s)"
        } catch (e: SecurityException) {
            _status.value = "BT: permiso denegado"
        } catch (_: Exception) {
            _status.value = "BT: error al escanear"
        }
    }

    fun sendViaBt(deviceAddress: String, text: String) {
        if (text.isBlank()) return
        val msg = ChatMessage(text, true, status = "sending")
        _chatMessages.value = _chatMessages.value + msg
        _status.value = "BT: enviando..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val btOut = btClientSocket?.outputStream
                if (btOut != null && btClientSocket?.isConnected == true) {
                    try {
                        btOut.write(encryptMsg(text))
                        btOut.flush()
                        setLastSentStatus("sent")
                        _status.value = "BT: mensaje enviado"
                        return@launch
                    } catch (_: Exception) {
                        btClientSocket = null
                    }
                }
                val device = btAdapter?.getRemoteDevice(deviceAddress)
                if (device == null) { setLastSentStatus("failed"); _status.value = "BT: dispositivo no encontrado"; return@launch }
                var socket: BluetoothSocket? = null
                try {
                    val m = device.javaClass.getMethod("createInsecureRfcommSocket", Integer.TYPE)
                    socket = m.invoke(device, 10) as? BluetoothSocket
                    socket?.connect()
                } catch (_: Exception) {
                    try {
                        socket = device.createInsecureRfcommSocketToServiceRecord(BT_UUID)
                        socket?.connect()
                    } catch (_: Exception) { socket = null }
                }
                if (socket?.isConnected == true) {
                    btClientSocket = socket
                    socket.outputStream.write(encryptMsg(text))
                    socket.outputStream.flush()
                    launch(Dispatchers.IO) { btReadLoop(socket) }
                    setLastSentStatus("sent")
                    _status.value = "BT: mensaje enviado"
                } else {
                    setLastSentStatus("failed")
                    _status.value = "BT: no se pudo conectar"
                }
            } catch (e: SecurityException) {
                setLastSentStatus("failed")
                _status.value = "BT: permiso denegado"
            } catch (e: java.io.IOException) {
                setLastSentStatus("failed")
                _status.value = "BT: error IO: ${e.message}"
            } catch (e: Exception) {
                setLastSentStatus("failed")
                _status.value = "BT: error: ${e.message}"
            }
        }
    }

    private suspend fun btReadLoop(socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            while (socket.isConnected) {
                val chunk = ByteArray(4096)
                val n = input.read(chunk)
                if (n <= 0) break
                val text = if (n >= 12) decryptMsg(chunk.copyOf(n)) else ""
                if (text.isNotEmpty()) {
                    _chatMessages.value = _chatMessages.value + ChatMessage(text, false)
                    vibrate()
                    _status.value = "BT: mensaje recibido"
                }
            }
        } catch (_: Exception) {}
        if (btClientSocket == socket) btClientSocket = null
        _status.value = "BT: desconectado"
    }

    fun selectBtDevice(addr: String, name: String) {
        _selectedBtDevice.value = Pair(name, addr)
        _status.value = "BT: destino $name seleccionado"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = btAdapter?.getRemoteDevice(addr)
                if (device != null) {
                    var socket: BluetoothSocket? = null
                    try {
                        val m = device.javaClass.getMethod("createInsecureRfcommSocket", Integer.TYPE)
                        socket = m.invoke(device, 10) as? BluetoothSocket
                        socket?.connect()
                    } catch (_: Exception) {
                        try {
                            socket = device.createInsecureRfcommSocketToServiceRecord(BT_UUID)
                            socket?.connect()
                        } catch (_: Exception) { socket = null }
                    }
                    if (socket?.isConnected == true) {
                        btClientSocket = socket
                        _status.value = "BT: conectado a $name canal 10"
                        launch(Dispatchers.IO) { btReadLoop(socket) }
                    }
                }
            } catch (e: Exception) {
                _status.value = "BT: no se pudo conectar al PC: ${e.message}"
            }
        }
    }

    fun clearBtSelection() {
        _selectedBtDevice.value = null
    }

    // ── Relay remoto (WebSocket) ──
    private val _chatRelayConnected = MutableStateFlow(false)
    val chatRelayConnected: StateFlow<Boolean> = _chatRelayConnected.asStateFlow()
    private var relayWs: WebSocket? = null
    private val okHttp = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
    private val _myGuestId = MutableStateFlow("")
    private val _guestList = MutableStateFlow<List<GuestInfo>>(emptyList())

    fun connectRelay(host: String, port: Int, guestName: String = "") {
        disconnectRelay()
        _status.value = "Conectando..."
        val cleanHost = host.trim().removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://").split("/").first().split(":").first()
        val protocol = if (port == 443) "wss" else "ws"
        val url = "$protocol://$cleanHost:$port"
        val nameToSend = if (guestName.isNotBlank()) guestName
            else context.getSharedPreferences("wifichat", Context.MODE_PRIVATE).getString("guest_name", "") ?: ""
        val request = okhttp3.Request.Builder().url(url).build()
        relayWs = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                _chatRelayConnected.value = true
                _status.value = "Conectado al relay"
                _chatMessages.value = _chatMessages.value + ChatMessage("--- Conectado al relay ---", false)
                if (nameToSend.isNotBlank()) {
                    ws.send("""{"type":"set_name","name":"$nameToSend"}""")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    when (json.optString("type")) {
                        "waiting" -> _status.value = "Relay: esperando otro dispositivo..."
                        "paired" -> {
                            _status.value = "Relay: conectado!"
                            _chatMessages.value = _chatMessages.value + ChatMessage("--- Conectado al relay! ---", false)
                        }
                        "peer_disconnected" -> {
                            _status.value = "Relay: el otro dispositivo se desconecto"
                            _chatMessages.value = _chatMessages.value + ChatMessage("--- El otro dispositivo se desconecto ---", false)
                        }
                        "message" -> {
                            val t = json.optString("text", "")
                            val from = json.optString("from", "Invitado")
                            if (t.isNotEmpty()) {
                                addIncomingMessage(t, fromName = from)
                            }
                        }
                        "user_joined" -> {
                            val name = json.optString("name", "Alguien")
                            val count = json.optInt("count", 0)
                            addIncomingMessage("--- $name se unio ($count en sala) ---")
                            _status.value = "$count dispositivos conectados"
                        }
                        "user_left" -> {
                            val name = json.optString("name", "Alguien")
                            val count = json.optInt("count", 0)
                            addIncomingMessage("--- $name se fue ($count restantes) ---")
                            if (count <= 1) _status.value = "Esperando otro dispositivo..."
                            else _status.value = "$count dispositivos conectados"
                        }
                        "user_renamed" -> {
                            val name = json.optString("name", "Alguien")
                            addIncomingMessage("--- Alguien ahora es $name ---")
                        }
                        "error" -> _status.value = "Relay error: ${json.optString("text", "")}"
                        "ok" -> _status.value = "Relay: ${json.optString("text", "")}"
                        "init" -> {
                            _myGuestId.value = json.optString("id", "")
                            val guestsArr = json.optJSONArray("guests")
                            if (guestsArr != null) {
                                val list = mutableListOf<GuestInfo>()
                                for (i in 0 until guestsArr.length()) {
                                    val g = guestsArr.getJSONObject(i)
                                    list.add(GuestInfo(g.optString("id", ""), g.optString("name", "")))
                                }
                                _guestList.value = list
                            }
                        }
                        "guest_list" -> {
                            val guestsArr = json.optJSONArray("guests")
                            if (guestsArr != null) {
                                val list = mutableListOf<GuestInfo>()
                                for (i in 0 until guestsArr.length()) {
                                    val g = guestsArr.getJSONObject(i)
                                    list.add(GuestInfo(g.optString("id", ""), g.optString("name", "")))
                                }
                                _guestList.value = list
                            }
                        }
                        "call-offer" -> {
                            val from = json.optString("from_id", "")
                            val fromName = json.optString("from_name", "Invitado")
                            val payload = json.optJSONObject("payload")
                            addIncomingMessage("--- OFERTA de $fromName ($from) ---")
                            if (payload != null && _callState.value == CallState.Idle) {
                                _callerId.value = from
                                _callerName.value = fromName
                                _callState.value = CallState.Ringing
                                val sdp = payload.optString("sdp", "")
                                val type = payload.optString("type", "offer")
                                viewModelScope.launch(Dispatchers.IO) {
                                    handleCallOffer(sdp, type)
                                }
                            } else if (payload != null) {
                                addIncomingMessage("--- Ocupado, rechazando... ---")
                                relayWs?.send("""{"type":"call-busy","to":"$from","payload":{}}""")
                            }
                        }
                        "call-answer" -> {
                            val payload = json.optJSONObject("payload")
                            if (payload != null) {
                                val sdp = payload.optString("sdp", "")
                                val type = payload.optString("type", "answer")
                                viewModelScope.launch(Dispatchers.IO) {
                                    handleCallAnswer(sdp, type)
                                }
                            }
                        }
                        "ice-candidate" -> {
                            val payload = json.optJSONObject("payload")
                            if (payload != null && _callState.value == CallState.Connected && peerConnection != null) {
                                val candidate = payload.optString("candidate", "")
                                val sdpMid = payload.optString("sdpMid", "")
                                val sdpMLineIndex = payload.optInt("sdpMLineIndex", 0)
                                if (candidate.isNotEmpty()) {
                                    peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
                                }
                            }
                        }
                        "call-hangup" -> {
                            viewModelScope.launch { hangup() }
                        }
                        "call-busy" -> {
                            _status.value = "La otra persona esta ocupada"
                            viewModelScope.launch { hangup() }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                _chatRelayConnected.value = false
                _status.value = "Error relay: ${t.message ?: "desconocido"}"
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _chatRelayConnected.value = false
                _status.value = "Relay desconectado"
            }
        })
    }

    fun disconnectRelay() {
        relayWs?.close(1000, "Usuario desconecto")
        relayWs = null
        _chatRelayConnected.value = false
        _status.value = "Relay desconectado"
    }

    fun setGuestName(name: String) {
        if (relayWs != null && name.isNotBlank()) {
            relayWs?.send("""{"type":"set_name","name":"$name"}""")
        }
    }

    fun selectGuest(id: String) {
        // Not needed
    }

    private fun setLastSentStatus(status: String) {
        val list = _chatMessages.value.toMutableList()
        for (i in list.indices.reversed()) {
            if (list[i].fromMe && list[i].status != "delivered" && list[i].status != "read") {
                list[i] = list[i].copy(status = status)
                break
            }
        }
        _chatMessages.value = list
    }

    fun sendChatMessage(ip: String, text: String) {
        if (text.isBlank()) return
        val msg = ChatMessage(text, true, status = "sending")
        _chatMessages.value = _chatMessages.value + msg
        if (_chatRelayConnected.value && relayWs != null) {
            val savedName = context.getSharedPreferences("wifichat", Context.MODE_PRIVATE).getString("guest_name", "Android") ?: "Android"
            val json = """{"type":"message","from":"${savedName.replace("\"","\\\"")}","text":"${text.replace("\"","\\\"")}"}"""
            relayWs?.send(json)
            setLastSentStatus("sent")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val btSocket = btClientSocket
            if (btSocket != null) {
                try {
                    btSocket.outputStream.write(encryptMsg(text))
                    btSocket.outputStream.flush()
                    setLastSentStatus("sent")
                    _status.value = "BT: mensaje enviado"
                    return@launch
                } catch (e: Exception) {
                    btClientSocket = null
                    _status.value = "BT: error socket: ${e.message}"
                }
            }
            val btTarget = _selectedBtDevice.value
            if (btTarget != null && btAdapter?.isEnabled == true) {
                _status.value = "Reconectando BT..."
                sendViaBt(btTarget.second, text)
                return@launch
            }
            try {
                if (ip.isBlank()) { _status.value = "Chat: no hay IP destino ni BT"; return@launch }
                val socket = Socket(ip, CHAT_PORT)
                socket.soTimeout = 5000
                socket.outputStream.write(encryptMsg(text))
                socket.outputStream.flush()
                socket.close()
                setLastSentStatus("sent")
                _status.value = "Chat: mensaje enviado"
            } catch (_: Exception) {
                setLastSentStatus("failed")
                val btDevice = _btDeviceList.value.firstOrNull { it.second == ip }?.second
                if (btDevice != null && btAdapter?.isEnabled == true) {
                    _status.value = "Reintentando Bluetooth..."
                    sendViaBt(btDevice, text)
                } else {
                    _status.value = "Chat: no se pudo enviar a $ip"
                }
            }
        }
    }

    // ── WebRTC Calling ──

    enum class CallState { Idle, Calling, Ringing, Connected }
    data class GuestInfo(val id: String, val name: String)

    private val _callState = MutableStateFlow(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callerName = MutableStateFlow("")
    val callerName: StateFlow<String> = _callerName.asStateFlow()

    private val _callerId = MutableStateFlow("")

    val myGuestId: StateFlow<String> = _myGuestId.asStateFlow()
    val guestList: StateFlow<List<GuestInfo>> = _guestList.asStateFlow()

    private var pcFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private val webrtcExecutor = Executors.newSingleThreadExecutor()
    private var pendingOfferSdp: String? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443").createIceServer()
    )

    private fun initWebRTC() {
        if (pcFactory != null) return
        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .createInitializationOptions()
            .also { PeerConnectionFactory.initialize(it) }
        pcFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection? {
        initWebRTC()
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val cid = _callerId.value
                if (cid.isNotEmpty() && relayWs != null) {
                    val payloadObj = org.json.JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                    val msgObj = org.json.JSONObject().apply {
                        put("type", "ice-candidate")
                        put("to", cid)
                        put("payload", payloadObj)
                    }
                    relayWs?.send(msgObj.toString())
                }
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track() ?: return
                if (track.kind() == "audio") {
                    remoteAudioTrack = track as? AudioTrack
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.DISCONNECTED || state == PeerConnection.IceConnectionState.FAILED) {
                    viewModelScope.launch { hangup() }
                } else if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    _callState.value = CallState.Connected
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(channel: DataChannel?) {}
        }
        peerConnection = pcFactory?.createPeerConnection(config, observer)
        return peerConnection
    }

    private fun addLocalAudio() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        localAudioSource = pcFactory?.createAudioSource(constraints)
        localAudioTrack = pcFactory?.createAudioTrack("audio_0", localAudioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("audio_0"))
    }

    private fun handleCallOffer(sdp: String, type: String) {
        pendingOfferSdp = sdp
    }

    private fun handleCallAnswer(sdp: String, type: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { _status.value = "Llamada establecida" }
            override fun onSetFailure(msg: String?) { _status.value = "setRemote: $msg" }
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(m: String?) {}
        }, desc)
    }

    fun startCall(targetId: String, targetName: String) {
        if (_callState.value != CallState.Idle) return
        _callState.value = CallState.Calling
        _callerName.value = targetName
        _callerId.value = targetId
        webrtcExecutor.execute {
            createPeerConnection()
            addLocalAudio()
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            val payloadObj = org.json.JSONObject().apply {
                                put("sdp", desc.description)
                                put("type", desc.type.canonicalForm())
                            }
                            val msgObj = org.json.JSONObject().apply {
                                put("type", "call-offer")
                                put("to", targetId)
                                put("payload", payloadObj)
                            }
                            val jsonStr = msgObj.toString()
                            val sent = relayWs?.send(jsonStr)
                            addIncomingMessage("--- Enviando oferta a $targetName (${if (sent == true) "OK" else "FALLO"}) ---")
                            _status.value = "Oferta enviada: ${jsonStr.take(100)}..."
                        }
                        override fun onSetFailure(msg: String?) { _status.value = "SDP set: $msg"; addIncomingMessage("--- Error SDP local: $msg ---"); hangup() }
                        override fun onCreateSuccess(d: SessionDescription) {}
                        override fun onCreateFailure(m: String?) {}
                    }, desc)
                }
                override fun onCreateFailure(msg: String?) { _status.value = "createOffer: $msg"; addIncomingMessage("--- Error createOffer: $msg ---"); hangup() }
                override fun onSetSuccess() {}
                override fun onSetFailure(msg: String?) {}
            }, constraints)
        }
    }

    fun answerCall() {
        if (_callState.value != CallState.Ringing) return
        _callState.value = CallState.Calling
        webrtcExecutor.execute {
            createPeerConnection()
            addLocalAudio()
            val offerSdp = pendingOfferSdp ?: return@execute
            pendingOfferSdp = null
            val offerDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    }
                    peerConnection?.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    val payloadObj = org.json.JSONObject().apply {
                                        put("sdp", desc.description)
                                        put("type", desc.type.canonicalForm())
                                    }
                                    val msgObj = org.json.JSONObject().apply {
                                        put("type", "call-answer")
                                        put("to", _callerId.value)
                                        put("payload", payloadObj)
                                    }
                                    relayWs?.send(msgObj.toString())
                                }
                                override fun onSetFailure(msg: String?) { _status.value = "SDP set: $msg"; hangup() }
                                override fun onCreateSuccess(d: SessionDescription) {}
                                override fun onCreateFailure(m: String?) {}
                            }, desc)
                        }
                        override fun onCreateFailure(msg: String?) { _status.value = "createAnswer: $msg"; hangup() }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(msg: String?) {}
                    }, constraints)
                }
                override fun onSetFailure(msg: String?) { _status.value = "setRemote: $msg"; hangup() }
                override fun onCreateSuccess(d: SessionDescription) {}
                override fun onCreateFailure(m: String?) {}
            }, offerDesc)
        }
    }

    fun rejectCall() {
        if (_callState.value != CallState.Ringing) return
        relayWs?.send("""{"type":"call-busy","to":"${_callerId.value}","payload":{}}""")
        pendingOfferSdp = null
        hangup()
    }

    fun hangup() {
        _callState.value = CallState.Idle
        val cid = _callerId.value
        if (cid.isNotEmpty() && relayWs != null) {
            relayWs?.send("""{"type":"call-hangup","to":"$cid","payload":{}}""")
        }
        _callerId.value = ""
        _callerName.value = ""
        pendingOfferSdp = null
        peerConnection?.close()
        peerConnection = null
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        remoteAudioTrack = null
    }

    fun clearChat() {
        _chatMessages.value = emptyList()
    }
}
