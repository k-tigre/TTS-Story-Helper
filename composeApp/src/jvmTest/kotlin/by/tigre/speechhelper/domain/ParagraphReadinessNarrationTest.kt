package by.tigre.speechhelper.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ParagraphReadinessNarrationTest {

    @Test
    fun classify_narrationWithoutVoiceTags_isNarrationOnly() {
        val original = "Глава 2. Мастерская на краю света"
        val label = ParagraphReadiness.classify(
            originalParagraph = original,
            markedParagraph = "",
            mapping = null,
            remarkupNeeded = false,
        )
        assertEquals(ParagraphReadinessLabel.NarrationOnly, label)
    }

    @Test
    fun classify_dialogWithoutVoiceTags_staysNoVoiceTags() {
        val original = "— Ладно, — хрипло проговорила она."
        val label = ParagraphReadiness.classify(
            originalParagraph = original,
            markedParagraph = "",
            mapping = null,
            remarkupNeeded = false,
        )
        assertEquals(ParagraphReadinessLabel.NoVoiceTags, label)
    }
}
