package it.michelegiammarini.sleeppausetv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LgWebOsProtocolTest {
    @Test fun pauseUsesWebOsMediaControlEndpoint() {
        val json = JSONObject(LgWebOsProtocol.pausePayload())
        assertEquals("request", json.getString("type"))
        assertEquals("ssap://media.controls/pause", json.getString("uri"))
        assertTrue(LgWebOsProtocol.remoteUrl("192.168.1.50").startsWith("ws://192.168.1.50:3000/"))
        assertTrue(LgWebOsProtocol.remoteUrl("192.168.1.50", secure = true).startsWith("wss://192.168.1.50:3001/"))
    }

    @Test fun registrationIncludesStoredClientKey() {
        val json = JSONObject(LgWebOsProtocol.registerPayload("abc123"))
        assertEquals("register", json.getString("type"))
        assertEquals("abc123", json.getJSONObject("payload").getString("client-key"))
    }

    @Test fun extractsClientKeyFromRegisteredMessage() {
        val message = """{"type":"registered","payload":{"client-key":"new-key"}}"""
        assertTrue(LgWebOsProtocol.isRegistered(message))
        assertEquals("new-key", LgWebOsProtocol.extractClientKey(message))
    }
}
