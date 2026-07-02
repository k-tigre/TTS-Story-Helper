package by.tigre.speechhelper.data

import kotlin.test.Test
import kotlin.test.assertTrue

class AutoMarkupOrderCheckTest {

    @Test
    fun verify_ignoresAnchorMarkersInOutput() {
        val source = "Он сказал что будет поздно."
        val marked = """
            ⟦p:0000⟧
            [voice_main]
            Он сказал что будет поздно.
            [/voice_main]
        """.trimIndent()
        val result = AutoMarkupOrderCheck.verify(source, marked)
        assertTrue(result.ok)
    }

    @Test
    fun verify_softOk_whenVoiceBoundariesShiftSlightly() {
        val source = "— Привет, — сказал он. — Как дела?"
        val marked = """
            [voice_actor]
            — Привет, —
            [/voice_actor]
            [voice_main]
            сказал он.
            [/voice_main]
            [voice_actor]
            — Как дела?
            [/voice_actor]
        """.trimIndent()
        val result = AutoMarkupOrderCheck.verify(source, marked)
        assertTrue(result.ok || result.softOk)
    }
}
