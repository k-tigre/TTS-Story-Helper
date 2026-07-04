package by.tigre.speechhelper.domain

/**
 * Состояние абзаца для UI.
 * [NoVoiceTags] — нет [voice] и в тексте, по эвристике, есть прямая речь (нужна разметка диалога).
 * [NarrationOnly] — нет [voice], но прямой речи не видно: достаточно обычного voice_main.
 */
enum class ParagraphReadinessLabel {
    Empty,
    /** Нет тегов голоса и, вероятно, есть диалог — попадёт в «Недостающие». */
    NoVoiceTags,
    /** Нет тегов, но диалога нет — для озвучки хватит одного voice_main. */
    NarrationOnly,
    /** Есть [voice_main], но прямая речь не вынесена в голос персонажа. */
    DialogUnsplit,
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
    data class ChapterValidationSummary(
        val readyCount: Int,
        val totalCount: Int,
        val remarkupCount: Int,
        val invalidCount: Int,
        val dialogUnsplitCount: Int,
        val noVoiceCount: Int,
    ) {
        val isAllReady: Boolean
            get() = totalCount > 0 && readyCount == totalCount
    }

    /**
     * Сводка для счётчика «Проверить»: учитывает сбой пакета, диалог без голоса и отсутствие [voice],
     * а не только [ParagraphMapping.isValid].
     */
    fun summarizeChapterValidation(
        validationResult: ValidationResult?,
        remarkupIndices: Set<Int>,
        originalJoined: String,
        markedParagraphs: List<String>,
    ): ChapterValidationSummary? {
        val origParas = TextParser.splitParagraphsForStorage(originalJoined)
        if (origParas.isEmpty()) return null
        val total = origParas.size
        var ready = 0
        var remarkup = 0
        var invalid = 0
        var dialogUnsplit = 0
        var noVoice = 0
        for (i in 0 until total) {
            when (
                classify(
                    origParas[i],
                    markedParagraphs.getOrElse(i) { "" },
                    validationResult?.paragraphs?.getOrNull(i),
                    remarkupNeeded = i in remarkupIndices,
                )
            ) {
                ParagraphReadinessLabel.MarkedValid,
                ParagraphReadinessLabel.NarrationOnly -> ready++
                ParagraphReadinessLabel.RemarkupNeeded -> remarkup++
                ParagraphReadinessLabel.MarkedInvalid -> invalid++
                ParagraphReadinessLabel.DialogUnsplit -> dialogUnsplit++
                ParagraphReadinessLabel.NoVoiceTags -> noVoice++
                ParagraphReadinessLabel.Empty,
                ParagraphReadinessLabel.MarkedUnvalidated,
                -> Unit
            }
        }
        return ChapterValidationSummary(
            readyCount = ready,
            totalCount = total,
            remarkupCount = remarkup,
            invalidCount = invalid,
            dialogUnsplitCount = dialogUnsplit,
            noVoiceCount = noVoice,
        )
    }

    fun classify(
        originalParagraph: String,
        markedParagraph: String,
        mapping: ParagraphMapping?,
        remarkupNeeded: Boolean,
    ): ParagraphReadinessLabel {
        if (remarkupNeeded) return ParagraphReadinessLabel.RemarkupNeeded
        if (originalParagraph.trim().isEmpty()) return ParagraphReadinessLabel.Empty
        if (mapping?.isValid == true) {
            // Multi-voice абзацы хранятся plain в marked rows ([refreshMarkedRowsForOriginals]).
            if (!TextParser.hasVoiceMarkers(markedParagraph)) {
                return ParagraphReadinessLabel.MarkedValid
            }
            if (TextParser.needsDialogVoiceSplit(markedParagraph, originalParagraph)) {
                return ParagraphReadinessLabel.DialogUnsplit
            }
            return ParagraphReadinessLabel.MarkedValid
        }
        if (!TextParser.hasVoiceMarkers(markedParagraph)) {
            val plain = originalParagraph.trim().ifBlank { TextParser.stripMarkup(markedParagraph).trim() }
            return if (TextParser.hasDirectSpeech(plain)) {
                ParagraphReadinessLabel.NoVoiceTags
            } else {
                ParagraphReadinessLabel.NarrationOnly
            }
        }
        if (TextParser.needsDialogVoiceSplit(markedParagraph, originalParagraph)) {
            return ParagraphReadinessLabel.DialogUnsplit
        }
        if (mapping == null) return ParagraphReadinessLabel.MarkedUnvalidated
        return if (mapping.isValid) ParagraphReadinessLabel.MarkedValid else ParagraphReadinessLabel.MarkedInvalid
    }
}
