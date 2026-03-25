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

    /**
     * Checks how well a sequence of original words can be found at the start of markup words.
     * Returns the number of original words matched and how many markup words were consumed.
     * A match is considered successful if at least [threshold] fraction of original words were found.
     */
    private fun tryMatchWords(
        origWords: List<String>,
        markupWords: List<String>,
        markupOffset: Int,
    ): Pair<Int, Int>? {
        if (origWords.isEmpty()) return null
        var origIdx = 0
        var markupIdx = markupOffset
        while (origIdx < origWords.size && markupIdx < markupWords.size) {
            if (origWords[origIdx].equals(markupWords[markupIdx], ignoreCase = true)) {
                origIdx++
            }
            markupIdx++
        }
        // Require at least 50% of original words matched to consider it a real match
        return if (origIdx >= origWords.size / 2) Pair(origIdx, markupIdx) else null
    }

    fun buildParagraphMapping(originalText: String, markupText: String): ValidationResult {
        val origParagraphs = originalText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
        val markupChunks = markupText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }

        if (origParagraphs.isEmpty()) {
            return ValidationResult(emptyList(), true, markupText.takeIf { it.isNotBlank() })
        }

        val origParaWords = origParagraphs.map { extractWords(stripMarkup(it)) }

        // Build a flat word list from all markup chunks, tracking chunk boundaries
        // chunkBoundaries[i] = index of first word in markupChunks[i] within the flat list
        val flatMarkupWords = mutableListOf<String>()
        val wordToChunkIdx = mutableListOf<Int>() // for each flat word, which chunk it belongs to
        for ((chunkI, chunk) in markupChunks.withIndex()) {
            val words = extractWords(stripMarkup(chunk))
            for (w in words) {
                flatMarkupWords.add(w)
                wordToChunkIdx.add(chunkI)
            }
        }

        // For each original paragraph, try to find its words starting from current position.
        // If match fails — paragraph is missing, don't consume markup words.
        var markupWordPos = 0
        val usedChunkIndices = mutableSetOf<Int>()

        val mappings = origParagraphs.mapIndexed { paraIdx, origPara ->
            val origWords = origParaWords[paraIdx]

            if (origWords.isEmpty()) {
                return@mapIndexed ParagraphMapping(
                    originalParagraph = origPara,
                    markupChunks = emptyList(),
                    isValid = true,
                    extraInMarkup = emptyList(),
                    missingInMarkup = emptyList(),
                )
            }

            val matchResult = tryMatchWords(origWords, flatMarkupWords, markupWordPos)

            if (matchResult == null) {
                // Could not match this paragraph — it's missing in markup
                ParagraphMapping(
                    originalParagraph = origPara,
                    markupChunks = emptyList(),
                    isValid = false,
                    extraInMarkup = emptyList(),
                    missingInMarkup = origWords,
                )
            } else {
                val (_, consumedUpTo) = matchResult
                // Collect which markup chunks were involved
                val involvedChunks = (markupWordPos until consumedUpTo)
                    .map { wordToChunkIdx[it] }
                    .distinct()
                    .sorted()

                val matchedChunks = involvedChunks.map { markupChunks[it] }
                usedChunkIndices.addAll(involvedChunks)

                // LCS comparison for detailed diff
                val markupWordsSlice = flatMarkupWords.subList(markupWordPos, consumedUpTo)
                val matchedOrigIndices = lcsIndicesA(origWords, markupWordsSlice)
                val matchedMarkupIndices = lcsIndicesB(origWords, markupWordsSlice)

                val missing = origWords.filterIndexed { i, _ -> i !in matchedOrigIndices }
                val extra = markupWordsSlice.filterIndexed { i, _ -> i !in matchedMarkupIndices }

                markupWordPos = consumedUpTo

                ParagraphMapping(
                    originalParagraph = origPara,
                    markupChunks = matchedChunks,
                    isValid = missing.isEmpty() && extra.isEmpty(),
                    extraInMarkup = extra,
                    missingInMarkup = missing,
                )
            }
        }

        // Collect unmatched markup chunks (not used by any paragraph)
        val unusedChunks = markupChunks.indices
            .filter { it !in usedChunkIndices }
            .map { markupChunks[it] }
        val unmatchedTail = unusedChunks.joinToString("\n\n").takeIf { it.isNotBlank() }

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
