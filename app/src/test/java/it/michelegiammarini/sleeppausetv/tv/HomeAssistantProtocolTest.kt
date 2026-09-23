package it.michelegiammarini.sleeppausetv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAssistantProtocolTest {
    @Test fun authorizationUsesConfiguredBearerToken() {
        assertEquals("Bearer secret-token", HomeAssistantProtocol.authorizationHeader("  secret-token  "))
    }
}
