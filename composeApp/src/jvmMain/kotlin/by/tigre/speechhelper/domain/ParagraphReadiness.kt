package by.tigre.speechhelper.domain

/**
 * Состояние абзаца для UI. [NoVoiceTags] — тот же критерий, что и режим «Недостающие» в авторазметке:
 * в тексте абзаца нет пар меток `[voice]…[/voice]`.
 */
enum class ParagraphReadinessLabel {
    Empty,
    /** В разметке этого абзаца нет тегов голоса — режим «недостающие» обработает его следующим среди «дырявых». */
    NoVoiceTags,
    /** Есть теги, но сверка с оригиналом не прошла. */
    MarkedInvalid,
    /** Есть теги и абзац валиден. */
    MarkedValid,
    /** Теги есть, но нет данных сопоставления (редко). */
    MarkedUnvalidated,
    /** Сбой пакетной авторазметки для этого абзаца. */
    RemarkupNeeded,
}

object ParagraphReadiness {
    fun classify(
        originalParagraph: String,
        markedParagraph: String,
        mapping: ParagraphMapping?,
        remarkupNeeded: Boolean,
    ): ParagraphReadinessLabel {
        if (remarkupNeeded) return ParagraphReadinessLabel.RemarkupNeeded
        if (originalParagraph.trim().isEmpty()) return ParagraphReadinessLabel.Empty
        if (!TextParser.hasVoiceMarkers(markedParagraph)) return ParagraphReadinessLabel.NoVoiceTags
        if (mapping == null) return ParagraphReadinessLabel.MarkedUnvalidated
        return if (mapping.isValid) ParagraphReadinessLabel.MarkedValid else ParagraphReadinessLabel.MarkedInvalid
    }
}
