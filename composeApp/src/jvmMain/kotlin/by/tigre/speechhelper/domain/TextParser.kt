package by.tigre.speechhelper.domain

data class TextSegment(
    val voiceName: String?,
    val text: String,
)

object TextParser {

    private val TAG_REGEX = Regex("""\[([^]/]+)](.*?)\[/\1]""", RegexOption.DOT_MATCHES_ALL)

    fun parse(input: String): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        var lastIndex = 0

        for (match in TAG_REGEX.findAll(input)) {
            if (match.range.first > lastIndex) {
                val before = input.substring(lastIndex, match.range.first).trim()
                if (before.isNotBlank()) {
                    segments.add(TextSegment(voiceName = null, text = before))
                }
            }
            val voice = match.groupValues[1].trim()
            val text = match.groupValues[2].trim()
            if (text.isNotBlank()) {
                segments.add(TextSegment(voiceName = voice, text = text))
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < input.length) {
            val tail = input.substring(lastIndex).trim()
            if (tail.isNotBlank()) {
                segments.add(TextSegment(voiceName = null, text = tail))
            }
        }

        return segments
    }

    fun extractVoiceNames(input: String): Set<String> {
        return TAG_REGEX.findAll(input).map { it.groupValues[1].trim() }.toSet()
    }

    fun hasVoiceMarkers(input: String): Boolean {
        return TAG_REGEX.containsMatchIn(input)
    }

    fun extractWords(text: String): List<String> {
        return text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
    }

    /**
     * Match original paragraphs to segments using word-based alignment.
     * Resilient to extra newlines, whitespace changes, and markup in segments.
     */
    fun matchParagraphsToSegments(
        originalParagraphs: List<String>,
        segments: List<TextSegment>,
    ): List<String> {
        if (originalParagraphs.isEmpty() || segments.isEmpty()) {
            return segments.map { "" }
        }

        // Build word lists for each original paragraph and each segment
        val origParaWords = originalParagraphs.map { extractWords(it) }
        val segmentWords = segments.map { extractWords(it.text) }

        // Greedy two-pointer alignment:
        // Walk through original paragraphs and segment words simultaneously.
        // For each segment, consume original paragraphs until we've matched enough words.
        var origParaIdx = 0
        var origWordIdx = 0 // word index within current original paragraph

        return segmentWords.mapIndexed { segIdx, segWords ->
            val startOrigPara = origParaIdx
            var segWordIdx = 0

            // Try to match words from this segment against original paragraphs
            while (segWordIdx < segWords.size && origParaIdx < origParaWords.size) {
                val origWords = origParaWords[origParaIdx]
                if (origWordIdx >= origWords.size) {
                    // Move to next original paragraph
                    origParaIdx++
                    origWordIdx = 0
                    continue
                }

                if (segWords[segWordIdx] == origWords[origWordIdx]) {
                    segWordIdx++
                    origWordIdx++
                } else {
                    // Words don't match — could be an edit in segment or original
                    // Try advancing segment word (added in markup)
                    segWordIdx++
                }
            }

            // If we partially consumed an original paragraph, include it
            val endOrigPara = if (origWordIdx > 0) origParaIdx + 1 else origParaIdx

            // For the last segment, include all remaining original paragraphs
            val actualEnd = if (segIdx == segments.size - 1) originalParagraphs.size else endOrigPara

            (startOrigPara until actualEnd)
                .mapNotNull { originalParagraphs.getOrNull(it) }
                .joinToString("\n\n")
        }
    }

    fun buildText(segments: List<TextSegment>): String {
        return segments.joinToString("\n\n") { segment ->
            if (segment.voiceName != null) {
                "[${segment.voiceName}]\n${segment.text}\n[/${segment.voiceName}]"
            } else {
                segment.text
            }
        }
    }
}
