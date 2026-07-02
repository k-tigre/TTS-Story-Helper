package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.TextParser

/**
 * Служебные маркеры абзацев и контекста для LLM-разметки.
 * Маркеры не попадают в storage/TTS — только transport round-trip.
 */
object AutoMarkupParagraphAnchors {

    const val DEFAULT_CONTEXT_CHARS = 350

    const val CTX_OPEN = "⟦ctx⟧"
    const val CTX_CLOSE = "⟦/ctx⟧"

    private val PARA_MARKER = Regex("""⟦p:(\d{4})⟧""")
    private val CTX_BLOCK = Regex("""⟦ctx⟧[\s\S]*?⟦/ctx⟧""", RegexOption.IGNORE_CASE)

    fun paraMarker(index: Int): String = "⟦p:${"%04d".format(index)}⟧"

    fun stripCtxBlock(text: String): String = CTX_BLOCK.replace(text, "").trim()

    /** Текст соседних абзацев: размеченный, если есть голоса, иначе оригинал. */
    fun paragraphTextForContext(original: String, working: String): String {
        val marked = working.trim()
        return if (marked.isNotBlank() && TextParser.hasVoiceMarkers(marked)) marked
        else original.trim()
    }

    fun buildPrecedingText(
        firstBatchIndex: Int,
        originals: List<String>,
        working: List<String>,
    ): String {
        if (firstBatchIndex <= 0) return ""
        val parts = (0 until firstBatchIndex).map { i ->
            paragraphTextForContext(originals[i], working[i])
        }.filter { it.isNotBlank() }
        return TextParser.joinParagraphsForStorage(parts)
    }

    fun buildContextPrefix(
        precedingText: String,
        contextChars: Int = DEFAULT_CONTEXT_CHARS,
    ): String? {
        val trimmed = precedingText.trim()
        if (trimmed.isEmpty()) return null
        val raw = trimmed.takeLast(contextChars.coerceAtLeast(1)).trimStart()
        val tail = trimToWordBoundary(raw)
        if (tail.isBlank()) return null
        return "$CTX_OPEN\n$tail\n$CTX_CLOSE"
    }

    internal fun trimToWordBoundary(tail: String): String {
        if (tail.isEmpty()) return tail
        val firstSpace = tail.indexOf(' ')
        return if (firstSpace in 1 until tail.length / 3) {
            tail.substring(firstSpace + 1).trim()
        } else {
            tail.trim()
        }
    }

    fun wrapBatchForLlm(
        sources: List<String>,
        precedingText: String = "",
        contextChars: Int = DEFAULT_CONTEXT_CHARS,
    ): String {
        val body = sources.mapIndexed { i, text ->
            "${paraMarker(i)}\n${text.trim()}"
        }.joinToString("\n\n")
        val ctx = buildContextPrefix(precedingText, contextChars)
        return if (ctx == null) body else "$ctx\n\n$body"
    }

    /** Длина обёртки без текста абзацев (контекст + маркеры). */
    fun payloadOverhead(
        paragraphCount: Int,
        precedingText: String = "",
        contextChars: Int = DEFAULT_CONTEXT_CHARS,
    ): Int {
        if (paragraphCount <= 0) return 0
        return wrapBatchForLlm(List(paragraphCount) { "" }, precedingText, contextChars).length
    }

    /**
     * Разбирает ответ модели по маркерам ⟦p:NNNN⟧.
     * Маркеры могут идти в файле не по порядку id — сопоставление по номеру ⟦p:…⟧.
     * @return null, если маркеров недостаточно или id не совпадают с 0..expectedCount-1.
     */
    fun parseMarkedBatch(markedOutput: String, expectedCount: Int): List<String>? {
        if (expectedCount <= 0) return emptyList()
        val cleaned = stripCtxBlock(markedOutput)
        val markers = PARA_MARKER.findAll(cleaned).toList()
        if (markers.size < expectedCount) return null

        val byId = mutableMapOf<Int, MatchResult>()
        for (marker in markers) {
            val id = marker.groupValues[1].toIntOrNull() ?: return null
            if (id in byId) return null
            byId[id] = marker
        }
        if (byId.keys != (0 until expectedCount).toSet()) return null

        val byPosition = markers.sortedBy { it.range.first }
        return (0 until expectedCount).map { id ->
            val marker = byId[id]!!
            val contentStart = marker.range.last + 1
            val contentEnd = byPosition
                .firstOrNull { it.range.first > marker.range.first }
                ?.range?.first ?: cleaned.length
            stripAnchorsFromText(cleaned.substring(contentStart, contentEnd).trim())
        }
    }

    fun stripAnchorsFromText(text: String): String =
        text.replace(PARA_MARKER, "")
            .replace(CTX_OPEN, "")
            .replace(CTX_CLOSE, "")
            .trim()

    /**
     * Сначала по маркерам ⟦p:…⟧; при неудаче — [AutoMarkupBatchAlign].
     */
    fun alignBatchOrFallback(sources: List<String>, markedOutput: String): List<String>? {
        parseMarkedBatch(markedOutput, sources.size)?.let { return it }
        val stripped = stripAnchorsFromText(markedOutput)
        return AutoMarkupBatchAlign.alignOrNull(sources, stripped)
    }
}
