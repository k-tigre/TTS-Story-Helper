package by.tigre.speechhelper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ParagraphReadinessSummaryTest {

    @Test
    fun summarize_countsRemarkupParagraphsAsNotReady() {
        val validation = ValidationResult(
            paragraphs = List(3) {
                ParagraphMapping(
                    originalParagraph = "a",
                    matchedSegmentIndices = listOf(0),
                    isValid = true,
                    extraInMarkup = emptyList(),
                    missingInMarkup = emptyList(),
                )
            },
            isFullyValid = true,
            unmatchedSegmentIndices = emptySet(),
        )
        val summary = ParagraphReadiness.summarizeChapterValidation(
            validationResult = validation,
            remarkupIndices = setOf(0, 1),
            originalJoined = "a\n\nb\n\nc",
            markedParagraphs = listOf(
                "[voice_main]\na\n[/voice_main]",
                "[voice_main]\nb\n[/voice_main]",
                "[voice_main]\nc\n[/voice_main]",
            ),
        )
        assertNotNull(summary)
        assertEquals(1, summary.readyCount)
        assertEquals(3, summary.totalCount)
        assertEquals(2, summary.remarkupCount)
        assertFalse(summary.isAllReady)
    }
}
