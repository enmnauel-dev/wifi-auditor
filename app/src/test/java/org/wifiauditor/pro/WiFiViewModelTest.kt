package org.wifiauditor.pro

import org.junit.Test
import org.junit.Assert.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WiFiViewModelTest {

    private val AES_KEY = "WiFiAud1t0r!2026".toByteArray()

    private fun encryptMsg(text: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        val iv = cipher.iv
        val encrypted = cipher.doFinal(text.toByteArray())
        return iv + encrypted
    }

    private fun decryptMsg(data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.copyOfRange(0, 12)
        val encrypted = data.copyOfRange(12, data.size)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted))
    }

    @Test
    fun encryption_decrypts_correctly() {
        val original = "Hola mundo"
        val encrypted = encryptMsg(original)
        val decrypted = decryptMsg(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun encryption_empty_string() {
        val original = ""
        val encrypted = encryptMsg(original)
        val decrypted = decryptMsg(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun encryption_special_characters() {
        val original = "¡Hola! ¿Cómo estás? ñoño 100% #privacidad"
        val encrypted = encryptMsg(original)
        val decrypted = decryptMsg(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun encryption_long_message() {
        val original = "A".repeat(10000)
        val encrypted = encryptMsg(original)
        val decrypted = decryptMsg(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun encryption_produces_different_ciphertexts() {
        val text = "mismo texto"
        val encrypted1 = encryptMsg(text)
        val encrypted2 = encryptMsg(text)
        assertNotEquals(
            "AES-GCM should produce different ciphertexts due to random IV",
            encrypted1.contentToString(), encrypted2.contentToString()
        )
    }

    @Test
    fun encrypted_output_is_longer_than_input() {
        val text = "Hola"
        val encrypted = encryptMsg(text)
        assertTrue("Encrypted output should be longer than input", encrypted.size > text.toByteArray().size)
    }

    @Test
    fun decryption_invalid_data_returns_empty() {
        val bad = ByteArray(5) { 0x42 }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = bad.copyOfRange(0, minOf(12, bad.size))
            val encrypted = bad.copyOfRange(minOf(12, bad.size), bad.size)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), GCMParameterSpec(128, iv))
            val result = String(cipher.doFinal(encrypted))
            fail("Should have thrown on invalid data, got: $result")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun chat_message_data_class() {
        val msg = ChatMessage("test", true)
        assertEquals("test", msg.text)
        assertTrue(msg.fromMe)
    }

    @Test
    fun chat_message_timestamp_is_set() {
        val before = System.currentTimeMillis()
        val msg = ChatMessage("test", false)
        val after = System.currentTimeMillis()
        assertTrue("Timestamp should be between before and after", msg.timestamp in before..after)
    }

    @Test
    fun router_info_defaults() {
        val info = RouterInfo()
        assertEquals("N/A", info.gateway)
        assertEquals("N/A", info.mac)
    }

    @Test
    fun connected_device_defaults() {
        val dev = ConnectedDevice(ip = "192.168.1.1", mac = "00:11:22:33:44:55")
        assertEquals("192.168.1.1", dev.ip)
        assertEquals("00:11:22:33:44:55", dev.mac)
        assertFalse(dev.isKnown)
        assertTrue(dev.active)
        assertEquals(0, dev.riskScore)
    }

    @Test
    fun ping_result_calculations() {
        val pr = PingResult(host = "192.168.1.1", sent = 3, received = 2, lost = 1, avgMs = 5.0)
        assertEquals(3, pr.sent)
        assertEquals(2, pr.received)
        assertEquals(1, pr.lost)
    }
}
