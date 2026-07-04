package by.tigre.speechhelper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextParserMarkedRowsTest {

    @Test
    fun refreshMarkedRows_splitsMultiParagraphVoiceBlockPerOriginal() {
        val p1 = "Пролог. Дождь стучал по крыше."
        val p2 = "Катя вела машину."
        val p3 = "Впереди сверкнула молния."
        val originals = listOf(p1, p2, p3)
        val markup = """
            [voice_main]
            $p1

            $p2

            $p3
            [/voice_main]
        """.trimIndent()

        val rows = TextParser.refreshMarkedRowsForOriginals(originals, markup)
        assertEquals(3, rows.size)
        assertTrue(rows[0].contains("[voice_main]") && rows[0].contains(p1))
        assertTrue(rows[1].contains("[voice_main]") && rows[1].contains(p2))
        assertTrue(rows[2].contains("[voice_main]") && rows[2].contains(p3))
    }

    @Test
    fun paragraphSharesMatchedSegmentsWithPrevious_whenSameSegment() {
        val originals = listOf("A", "B", "C")
        val markup = "[voice_main]\nA\n\nB\n\nC\n[/voice_main]"
        val segments = TextParser.parse(markup)
        val result = TextParser.buildParagraphMapping(originals, segments)
        assertTrue(TextParser.paragraphSharesMatchedSegmentsWithPrevious(1, result))
        assertTrue(TextParser.paragraphSharesMatchedSegmentsWithPrevious(2, result))
        assertEquals(false, TextParser.paragraphSharesMatchedSegmentsWithPrevious(0, result))
    }
}
