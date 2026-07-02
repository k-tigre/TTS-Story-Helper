package by.tigre.speechhelper.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoMarkupParagraphAnchorsTest {

    @Test
    fun wrapBatch_includesContextAndParaMarkers() {
        val payload = AutoMarkupParagraphAnchors.wrapBatchForLlm(
            sources = listOf("Абзац один.", "Абзац два."),
            precedingText = "Предыдущий длинный контекст с диалогом — привет.",
        )
        assertTrue(payload.startsWith(AutoMarkupParagraphAnchors.CTX_OPEN))
        assertTrue(payload.contains(AutoMarkupParagraphAnchors.paraMarker(0)))
        assertTrue(payload.contains(AutoMarkupParagraphAnchors.paraMarker(1)))
        assertTrue(payload.contains("Абзац один."))
    }

    @Test
    fun buildPrecedingText_prefersMarkedNeighborsWithVoices() {
        val preceding = AutoMarkupParagraphAnchors.buildPrecedingText(
            firstBatchIndex = 2,
            originals = listOf("orig0", "orig1", "batch"),
            working = listOf(
                "plain",
                "[voice_actor]\n— Привет\n[/voice_actor]",
                "",
            ),
        )
        assertTrue(preceding.contains("[voice_actor]"))
        assertTrue(preceding.contains("orig0"))
    }

    @Test
    fun parseMarkedBatch_extractsParagraphsInOrder() {
        val output = """
            ⟦p:0000⟧
            [voice_main]
            Первый.
            [/voice_main]

            ⟦p:0001⟧
            [voice_actor]
            Второй.
            [/voice_actor]
        """.trimIndent()
        val parsed = AutoMarkupParagraphAnchors.parseMarkedBatch(output, 2)
        assertNotNull(parsed)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].contains("Первый"))
        assertTrue(parsed[1].contains("Второй"))
        assertTrue(parsed.all { !it.contains("⟦p:") })
    }

    @Test
    fun parseMarkedBatch_rejectsMissingMarker() {
        val output = "⟦p:0000⟧\nТолько один."
        assertNull(AutoMarkupParagraphAnchors.parseMarkedBatch(output, 2))
    }

    @Test
    fun parseMarkedBatch_rejectsOutOfOrderIds() {
        val output = """
            ⟦p:0001⟧
            Второй

            ⟦p:0000⟧
            Первый
        """.trimIndent()
        assertNull(AutoMarkupParagraphAnchors.parseMarkedBatch(output, 2))
    }

    @Test
    fun alignBatchOrFallback_usesDpWhenMarkersMissing() {
        val sources = listOf("Короткий один.", "Короткий два.")
        val marked = "Короткий один.\n\nКороткий два."
        val aligned = AutoMarkupParagraphAnchors.alignBatchOrFallback(sources, marked)
        assertNotNull(aligned)
        assertEquals(2, aligned.size)
    }

    @Test
    fun stripAnchorsFromText_removesContextMarkers() {
        val clean = AutoMarkupParagraphAnchors.stripAnchorsFromText(
            "${AutoMarkupParagraphAnchors.CTX_OPEN}\nctx\n${AutoMarkupParagraphAnchors.CTX_CLOSE}",
        )
        assertEquals("ctx", clean)
    }

    @Test
    fun trimToWordBoundary_skipsPartialLeadingWord() {
        val tail = AutoMarkupParagraphAnchors.trimToWordBoundary("бросок длинного предложения")
        assertEquals("длинного предложения", tail)
    }
}
