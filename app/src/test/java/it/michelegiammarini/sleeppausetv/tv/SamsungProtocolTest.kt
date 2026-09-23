package it.michelegiammarini.sleeppausetv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungProtocolTest {
    @Test fun pausePayloadUsesExactSamsungKey() {
        val json = JSONObject(SamsungProtocol.keyPayload("KEY_PAUSE"))
        assertEquals("ms.remote.control", json.getString("method"))
        assertEquals("KEY_PAUSE", json.getJSONObject("params").getString("DataOfCmd"))
    }

    @Test fun urlIncludesPersistedToken() {
        val url = SamsungProtocol.remoteUrl("192.168.1.40", "123456")
        assertTrue(url.startsWith("wss://192.168.1.40:8002/"))
        assertTrue(url.contains("token=123456"))
        assertFalse(url.contains("SleepPause TV"))
        assertTrue(SamsungProtocol.remoteUrl("192.168.1.40", "", secure = false).startsWith("ws://192.168.1.40:8001/"))
    }

    @Test fun tokenIsExtractedFromConnectEvent() {
        val message = """{"event":"ms.channel.connect","data":{"token":"987654"}}"""
        assertEquals("987654", SamsungProtocol.extractToken(message))
    }
}
