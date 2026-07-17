package org.wifiauditor.pro

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun chat_message_creation() {
        val msg = ChatMessage("Hello", true)
        assertEquals("Hello", msg.text)
        assertTrue(msg.fromMe)
    }

    @Test
    fun router_info_creation() {
        val info = RouterInfo(gateway = "192.168.1.1", mac = "00:11:22:33:44:55")
        assertEquals("192.168.1.1", info.gateway)
    }
}
