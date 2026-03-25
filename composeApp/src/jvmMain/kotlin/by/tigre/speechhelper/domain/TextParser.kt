package by.tigre.speechhelper.domain

data class TextSegment(
    val voiceName: String?,
    val text: String,
)

data class ParagraphMapping(
    val originalParagraph: String,
    val markupChunks: List<String>,
    val isValid: Boolean,
    val extraInMarkup: List<String>,
    val missingInMarkup: List<String>,
)

data class ValidationResult(
    val paragraphs: List<ParagraphMapping>,
    val isFullyValid: Boolean,
    val unmatchedMarkupTail: String?,
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

    // ── Markup stripping ────────────────────────────────────────────────────

    private val PAUSE_REGEX = Regex("""<\[[^\]]*\]>""")

    fun stripMarkup(text: String): String {
        var result = TAG_REGEX.replace(text) { it.groupValues[2] }
        result = PAUSE_REGEX.replace(result, "")
        return result.replace(Regex("\\s+"), " ").trim()
    }

    // ── LCS utilities ────────────────────────────────────────────────────────

    /**
     * Returns indices from [a] that are part of the longest common subsequence with [b].
     * Case-insensitive word comparison.
     */
    fun lcsIndicesA(a: List<String>, b: List<String>): Set<Int> {
        val m = a.size; val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1].equals(b[j - 1], ignoreCase = true)) dp[i - 1][j - 1] + 1
                           else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        val matched = mutableSetOf<Int>()
        var i = m; var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1].equals(b[j - 1], ignoreCase = true) -> { matched.add(i - 1); i--; j-- }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return matched
    }

    /**
     * Returns indices from [b] that are part of the longest common subsequence with [a].
     */
    fun lcsIndicesB(a: List<String>, b: List<String>): Set<Int> {
        val m = a.size; val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1].equals(b[j - 1], ignoreCase = true)) dp[i - 1][j - 1] + 1
                           else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        val matched = mutableSetOf<Int>()
        var i = m; var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1].equals(b[j - 1], ignoreCase = true) -> { matched.add(j - 1); i--; j-- }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return matched
    }

    // ── Paragraph validation ─────────────────────────────────────────────────

    fun buildParagraphMapping(originalText: String, markupText: String): ValidationResult {
        val origParagraphs = originalText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
        val markupChunks = markupText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }

        if (origParagraphs.isEmpty()) {
            return ValidationResult(emptyList(), true, markupText.takeIf { it.isNotBlank() })
        }

        val origParaWords = origParagraphs.map { extractWords(stripMarkup(it)) }
        val markupChunkWords = markupChunks.map { extractWords(stripMarkup(it)) }

        // Greedy: for each original paragraph, consume markup chunks until all its words are matched
        var chunkIdx = 0
        var chunkWordOffset = 0

        val mappings = origParagraphs.mapIndexed { paraIdx, origPara ->
            val origWords = origParaWords[paraIdx]
            val startChunk = chunkIdx
            var origWordIdx = 0

            while (origWordIdx < origWords.size && chunkIdx < markupChunkWords.size) {
                val chunkWords = markupChunkWords[chunkIdx]
                if (chunkWordOffset >= chunkWords.size) {
                    chunkIdx++
                    chunkWordOffset = 0
                    continue
                }
                if (origWords[origWordIdx].equals(chunkWords[chunkWordOffset], ignoreCase = true)) {
                    origWordIdx++
                    chunkWordOffset++
                } else {
                    chunkWordOffset++
                }
            }

            // Include partially consumed chunk
            val endChunk = if (chunkWordOffset > 0) chunkIdx + 1 else chunkIdx

            // Last paragraph gets all remaining chunks
            val actualEnd = if (paraIdx == origParagraphs.size - 1) markupChunks.size else endChunk

            val matchedChunks = (startChunk until actualEnd).mapNotNull { markupChunks.getOrNull(it) }

            // Now validate: compare original words vs stripped markup words
            val strippedMarkupText = matchedChunks.joinToString(" ") { stripMarkup(it) }
            val markupWords = extractWords(strippedMarkupText)

            val matchedOrigIndices = lcsIndicesA(origWords, markupWords)
            val matchedMarkupIndices = lcsIndicesB(origWords, markupWords)

            val missing = origWords.filterIndexed { i, _ -> i !in matchedOrigIndices }
            val extra = markupWords.filterIndexed { i, _ -> i !in matchedMarkupIndices }

            ParagraphMapping(
                originalParagraph = origPara,
                markupChunks = matchedChunks,
                isValid = missing.isEmpty() && extra.isEmpty(),
                extraInMarkup = extra,
                missingInMarkup = missing,
            )
        }

        // Check for unmatched markup tail
        val unmatchedTail = if (chunkIdx < markupChunks.size && chunkWordOffset == 0) {
            (chunkIdx until markupChunks.size).mapNotNull { markupChunks.getOrNull(it) }.joinToString("\n\n")
        } else null

        return ValidationResult(
            paragraphs = mappings,
            isFullyValid = mappings.all { it.isValid } && unmatchedTail == null,
            unmatchedMarkupTail = unmatchedTail,
        )
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
