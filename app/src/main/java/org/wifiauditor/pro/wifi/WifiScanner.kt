package org.wifiauditor.pro.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager


data class WiFiNetwork(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val capabilities: String,
    val auth: String = "",
    val cipher: String = "",
    val band: String = "",
    val score: Int = 100,
    val risk: String = "SEGURO",
    val issues: List<String> = emptyList()
)

class WifiScanner(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun startScan() = wifiManager.startScan()

    fun getCachedResults(): List<ScanResult> = wifiManager.scanResults ?: emptyList()

    companion object {
        fun parseNetwork(sr: ScanResult): WiFiNetwork {
            @Suppress("DEPRECATION")
            val ssid = sr.SSID.ifEmpty { "<Oculta>" }
            @Suppress("DEPRECATION")
            val bssid = sr.BSSID ?: "N/A"
            val level = sr.level
            val freq = sr.frequency
            val caps = sr.capabilities ?: ""

            val (auth, cipher) = parseCapabilities(caps)
            val band = if (freq > 4000) "5 GHz" else if (freq > 2000) "2.4 GHz" else "N/A"
            val (score, risk, issues) = analyzeSecurity(auth, cipher, level, band)

            return WiFiNetwork(ssid, bssid, level, freq, caps, auth, cipher, band, score, risk, issues)
        }

        private fun parseCapabilities(caps: String): Pair<String, String> {
            var auth = "Desconocido"
            var cipher = "Desconocido"
            val c = caps.uppercase()

            when {
                "[WPA3-SAE]" in c || "SAE" in c -> auth = "WPA3-SAE"
                "[WPA3]" in c -> auth = "WPA3"
                "[WPA2-EAP]" in c -> auth = "WPA2-EAP"
                "[WPA2-PSK]" in c || "WPA2-PSK" in c || "[WPA2]" in c || "PSK" in c && "WPA2" in c -> auth = "WPA2-PSK"
                "[WPA-PSK]" in c || "WPA-PSK" in c || "[WPA]" in c -> auth = "WPA-PSK"
                "[WEP]" in c -> auth = "WEP"
                "[ESS]" in c -> auth = "WPA2-PSK"
                else -> auth = "OPEN"
            }

            when {
                "CCMP" in c -> cipher = "CCMP"
                "GCMP" in c -> cipher = "GCMP"
                "TKIP" in c -> cipher = "TKIP"
                else -> cipher = "AES"
            }

            return Pair(auth, cipher)
        }

        private fun analyzeSecurity(
            auth: String, cipher: String, level: Int, band: String
        ): Triple<Int, String, List<String>> {
            val issues = mutableListOf<String>()
            var score = 100
            val au = auth.uppercase()
            val ci = cipher.uppercase()

            when {
                "OPEN" in au -> { issues.add("ALTO: Red abierta"); score -= 50 }
                "WEP" in au -> { issues.add("ALTO: Usa WEP"); score -= 45 }
                au == "WPA-PSK" -> { issues.add("MEDIO: WPA original"); score -= 20 }
            }

            if ("TKIP" in ci) {
                issues.add("MEDIO: Cifrado TKIP")
                score -= 15
            }

            if ("WPA3" !in au && "OPEN" !in au) {
                issues.add("BAJO: Sin WPA3")
            }

            when {
                level < -80 -> issues.add("INFO: Senal muy debil")
                level < -70 -> issues.add("INFO: Senal debil")
            }

            if ("2.4" in band && "5" !in band) {
                issues.add("BAJO: Solo 2.4GHz")
            }

            score = maxOf(0, minOf(100, score))
            val risk = when {
                score >= 80 -> "SEGURO"
                score >= 60 -> "MEDIO"
                score >= 40 -> "RIESGO"
                else -> "CRITICO"
            }

            return Triple(score, risk, issues)
        }
    }
}
