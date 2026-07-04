package by.tigre.speechhelper.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ParagraphReadinessMultiVoiceTest {

    private val dialogOriginal =
        "— Мы пришли с предложением, — Яна протянула ему стаканчик. — Глинтвейн. Безалкогольный, но с корицей. Ты пей, а мы расскажем."

    @Test
    fun classify_validMultiVoiceMapping_isMarkedValidEvenWhenMarkedRowPlain() {
        val segments = listOf(
            TextSegment("voice_yana", "— Мы пришли с предложением, —"),
            TextSegment("voice_main", "Яна протянула ему стаканчик."),
            TextSegment("voice_yana", "— Глинтвейн. Безалкогольный, но с корицей. Ты пей, а мы расскажем."),
        )
        val mapping = TextParser.buildParagraphMapping(listOf(dialogOriginal), segments).paragraphs.single()

        assertEquals(true, mapping.isValid)

        val markedRow = TextParser.refreshMarkedRowsForOriginals(
            listOf(dialogOriginal),
            TextParser.buildText(segments),
        ).single()

        assertEquals(false, TextParser.hasVoiceMarkers(markedRow))

        val label = ParagraphReadiness.classify(
            originalParagraph = dialogOriginal,
            markedParagraph = markedRow,
            mapping = mapping,
            remarkupNeeded = false,
        )
        assertEquals(ParagraphReadinessLabel.MarkedValid, label)
    }
}
