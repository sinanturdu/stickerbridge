package dev.stickerbridge.app.convert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VideoDiagnosticsTest {
    @Test fun failureRetainsStageWithoutLoggingExceptionPayload() {
        val messages = mutableListOf<String>()
        val trace = VideoDiagnostics { messages += it }
        trace.step(VideoDiagnostics.Stage.PUBLISH, "bytes" to 128)
        trace.failure(IOException("PRIVATE_CANARY", IllegalStateException("NESTED_CANARY")))
        val log = messages.joinToString("\n")
        assertTrue(log.contains("stage=PUBLISH"))
        assertTrue(log.contains("bytes=128"))
        assertTrue(log.contains("error=IO"))
        assertFalse(log.contains("PRIVATE_CANARY"))
        assertFalse(log.contains("NESTED_CANARY"))
        assertFalse(log.contains("IOException"))
    }
}
