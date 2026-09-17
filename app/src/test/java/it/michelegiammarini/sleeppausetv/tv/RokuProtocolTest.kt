package it.michelegiammarini.sleeppausetv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuProtocolTest {
    @Test fun recognizesPlayingState() {
        assertTrue(RokuProtocol.isPlaying("<player state=\"play\"><plugin /></player>"))
        assertTrue(RokuProtocol.isPlaying("<state>playing</state>"))
        assertFalse(RokuProtocol.isPlaying("<state>pause</state>"))
    }

    @Test fun recognizesSafeNoOpStates() {
        assertTrue(RokuProtocol.isAlreadyPausedOrStopped("<state>pause</state>"))
        assertTrue(RokuProtocol.isAlreadyPausedOrStopped("<state>stopped</state>"))
        assertFalse(RokuProtocol.isAlreadyPausedOrStopped("<state>play</state>"))
    }
}
