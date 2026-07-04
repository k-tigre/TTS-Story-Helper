package by.tigre.speechhelper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextParserParagraphMappingTest {

    private val para6 = """
        «Ладно, — мысленно сказала она, чувствуя холодок под рёбрами. — Местная флора не только светится, но и следит. Не трогать, не нюхать, не пробовать на зуб». В голове всё равно заскрипело: откуда энергия? Фотосинтез? Маловероятно. Но образцов не взять — реакция непредсказуема.
    """.trimIndent()

    private val para7 = """
        Она обошла грибную колонию широкой дугой. Несколько «голов» повернулись синхронно. Одна, поменьше, наклонилась, будто разглядывая застёжку на рюкзаке. Катя ускорила шаг. Камни странной структуры отдавали тусклым отсветом. Листья шелестели не только от ветра — казалось, перешёптываются. Катя чувствовала себя чужой на чужом испытании: все смотрят, все измеряют, а правил игры никто не объяснил.
    """.trimIndent()

    private fun segmentsForDialogParagraph(): List<TextSegment> = listOf(
        TextSegment("voice_katya", "— Ладно, —"),
        TextSegment("voice_main", "мысленно сказала она, чувствуя холодок под рёбрами."),
        TextSegment("voice_katya", "— Местная флора не только светится, но и следит. Не трогать, не нюхать, не пробовать на зуб."),
        TextSegment("voice_main", "В голове всё равно заскрипело: откуда энергия? Фотосинтез? Маловероятно. Но образцов не взять — реакция непредсказуема."),
        TextSegment("voice_main", para7),
    )

    @Test
    fun buildParagraphMapping_dialogParagraphThenNarration_mapsDistinctSegments() {
        val originals = listOf(para6, para7)
        val segments = segmentsForDialogParagraph()
        val result = TextParser.buildParagraphMapping(originals, segments)

        assertEquals(2, result.paragraphs.size)
        val p6 = result.paragraphs[0]
        val p7 = result.paragraphs[1]

        assertTrue(p6.isValid, "para6 should be valid: missing=${p6.missingInMarkup} extra=${p6.extraInMarkup}")
        assertTrue(p7.isValid, "para7 should be valid: missing=${p7.missingInMarkup} extra=${p7.extraInMarkup}")

        assertEquals(listOf(0, 1, 2, 3), p6.matchedSegmentIndices)
        assertEquals(listOf(4), p7.matchedSegmentIndices)
        assertFalse(TextParser.paragraphSharesMatchedSegmentsWithPrevious(1, result))
    }

    @Test
    fun buildParagraphMapping_withLeadingParagraphs_doesNotReuseLastSegment() {
        val leading = List(5) { i -> "Короткий вводный абзац номер ${i + 1}." }
        val originals = leading + listOf(para6, para7)
        val leadingSegments = leading.map { TextSegment("voice_main", it) }
        val segments = leadingSegments + segmentsForDialogParagraph()
        val result = TextParser.buildParagraphMapping(originals, segments)

        val p6 = result.paragraphs[5]
        val p7 = result.paragraphs[6]
        assertTrue(p6.isValid, "para6: ${p6.matchedSegmentIndices} missing=${p6.missingInMarkup}")
        assertTrue(p7.isValid, "para7: ${p7.matchedSegmentIndices} missing=${p7.missingInMarkup}")
        assertEquals(listOf(5, 6, 7, 8), p6.matchedSegmentIndices)
        assertEquals(listOf(9), p7.matchedSegmentIndices)
        assertFalse(TextParser.paragraphSharesMatchedSegmentsWithPrevious(6, result))
    }

    @Test
    fun normalizeMarkupAfterAi_doesNotMergeTwoLongNarrationBlocks() {
        val markup = TextParser.buildText(
            listOf(
                TextSegment("voice_main", "В голове всё равно заскрипело: откуда энергия? Фотосинтез? Маловероятно. Но образцов не взять — реакция непредсказуема."),
                TextSegment("voice_main", para7),
            ),
        )
        val normalized = TextParser.normalizeMarkupAfterAi(markup)
        assertEquals(2, TextParser.parse(normalized).size)
    }

    @Test
    fun paragraphSharesMatchedSegmentsWithPrevious_tailSegmentOnly() {
        val originals = listOf(para6, para7)
        val segments = listOf(
            TextSegment("voice_katya", "— Ладно, —"),
            TextSegment("voice_main", "мысленно сказала она, чувствуя холодок под рёбрами."),
            TextSegment("voice_katya", "— Местная флора не только светится, но и следит. Не трогать, не нюхать, не пробовать на зуб."),
            TextSegment(
                "voice_main",
                "В голове всё равно заскрипело: откуда энергия? Фотосинтез? Маловероятно. Но образцов не взять — реакция непредсказуема.\n\n$para7",
            ),
        )
        val result = TextParser.buildParagraphMapping(originals, segments)
        assertTrue(TextParser.paragraphSharesMatchedSegmentsWithPrevious(1, result))
    }

    @Test
    fun displaySegmentIndices_usesMarkedParagraphSlice() {
        val segments = segmentsForDialogParagraph()
        val marked = "[voice_main]\n$para7\n[/voice_main]"
        val indices = TextParser.displaySegmentIndicesForParagraph(
            paragraphIndex = 1,
            markedParagraph = marked,
            validation = null,
            allSegments = segments,
            searchStartIndex = 0,
        )
        assertEquals(listOf(4), indices)
    }
    @Test
    fun refreshMarkedRows_multiVoiceParagraph_staysPlainPerRow() {
        val originals = listOf(para6, para7)
        val markup = TextParser.buildText(segmentsForDialogParagraph())
        val rows = TextParser.refreshMarkedRowsForOriginals(originals, markup)
        assertEquals(2, rows.size)
        assertFalse(TextParser.hasVoiceMarkers(rows[0]))
        assertTrue(TextParser.hasVoiceMarkers(rows[1]))
    }
}
