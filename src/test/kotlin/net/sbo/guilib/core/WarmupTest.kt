package net.sbo.guilib.core

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class WarmupTest {
    @Test
    fun warmsUpWithTheRealUserAgentStylesheetWithoutErrors() {
        val warnings = ArrayList<String>()
        val sink = Log.sink
        Log.sink = { level, msg -> if (level >= Log.Level.WARN) warnings += msg }
        try {
            Warmup.start(javaClass.getResource("/assets/guilib/css/ua.css")!!.readText())
            Warmup.await()
        } finally {
            Log.sink = sink
        }
        assertNotNull(Warmup.userAgent)
        assert(warnings.isEmpty()) { warnings.joinToString("\n") }
    }
}
