package by.tigre.speechhelper.domain

data class TextSegment(
    val voiceName: String?,
    val text: String,
)

data class ParagraphMapping(
    val originalParagraph: String,
    val matchedSegmentIndices: List<Int>,
    val isValid: Boolean,
    val extraInMarkup: List<String>,
    val missingInMarkup: List<String>,
)

data class ValidationResult(
    val paragraphs: List<ParagraphMapping>,
    val isFullyValid: Boolean,
    val unmatchedSegmentIndices: Set<Int>,
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
     * Extract words from text for comparison: strip markup, split by whitespace, lowercase.
     * Strict comparison — words are compared as-is (with punctuation), only lowercased.
     */
    fun extractCompareWords(text: String): List<String> {
        val stripped = stripMarkup(text)
        return stripped.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
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
     * Compare original text (as source of truth) against parsed segments.
     *
     * Algorithm:
     * 1. Split original text into paragraphs
     * 2. For each paragraph, search for the best matching window of consecutive segments
     *    (starting from where the previous paragraph ended)
     * 3. Match is based on LCS coverage >= 50% of paragraph words
     * 4. Unmatched segments are collected separately
     */
    fun buildParagraphMapping(originalText: String, segments: List<TextSegment>): ValidationResult {
        val origParagraphs = originalText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }

        if (origParagraphs.isEmpty()) {
            return ValidationResult(
                paragraphs = emptyList(),
                isFullyValid = segments.isEmpty(),
                unmatchedSegmentIndices = segments.indices.toSet(),
            )
        }

        // Build flat word list from all segments, tracking which segment each word belongs to
        val flatWords = mutableListOf<String>()
        val wordToSegIdx = mutableListOf<Int>()
        for ((segIdx, seg) in segments.withIndex()) {
            val words = extractCompareWords(seg.text)
            for (w in words) {
                flatWords.add(w)
                wordToSegIdx.add(segIdx)
            }
        }

        val origParaWords = origParagraphs.map { extractCompareWords(it) }

        // Track usage
        val usedSegmentIndices = mutableSetOf<Int>()
        var flatWordPos = 0

        val mappings = origParagraphs.mapIndexed { paraIdx, origPara ->
            val origWords = origParaWords[paraIdx]

            if (origWords.isEmpty()) {
                return@mapIndexed ParagraphMapping(
                    originalParagraph = origPara,
                    matchedSegmentIndices = emptyList(),
                    isValid = true,
                    extraInMarkup = emptyList(),
                    missingInMarkup = emptyList(),
                )
            }

            // Try to match this paragraph's words starting from current flatWordPos
            val matchResult = tryMatchSequence(origWords, flatWords, flatWordPos)

            if (matchResult == null) {
                ParagraphMapping(
                    originalParagraph = origPara,
                    matchedSegmentIndices = emptyList(),
                    isValid = false,
                    extraInMarkup = emptyList(),
                    missingInMarkup = origWords,
                )
            } else {
                val (_, consumedUpTo) = matchResult
                // Which segments were involved
                val involvedSegments = (flatWordPos until consumedUpTo)
                    .map { wordToSegIdx[it] }
                    .distinct()
                    .sorted()
                usedSegmentIndices.addAll(involvedSegments)

                // Detailed word diff via LCS
                val markupWordsSlice = flatWords.subList(flatWordPos, consumedUpTo)
                val matchedOrigIndices = lcsIndicesA(origWords, markupWordsSlice)
                val matchedMarkupIndices = lcsIndicesB(origWords, markupWordsSlice)

                val missing = origWords.filterIndexed { i, _ -> i !in matchedOrigIndices }
                val extra = markupWordsSlice.filterIndexed { i, _ -> i !in matchedMarkupIndices }

                flatWordPos = consumedUpTo

                ParagraphMapping(
                    originalParagraph = origPara,
                    matchedSegmentIndices = involvedSegments,
                    isValid = missing.isEmpty() && extra.isEmpty(),
                    extraInMarkup = extra,
                    missingInMarkup = missing,
                )
            }
        }

        val unmatchedSegments = segments.indices.filter { it !in usedSegmentIndices }.toSet()

        return ValidationResult(
            paragraphs = mappings,
            isFullyValid = mappings.all { it.isValid } && unmatchedSegments.isEmpty(),
            unmatchedSegmentIndices = unmatchedSegments,
        )
    }

    /**
     * Try to match origWords in flatWords starting from offset.
     * Walks through both lists; if an orig word matches the current flat word, both advance.
     * Otherwise only flat word advances (allows extra words in markup).
     * Returns (matched count, consumed up to) or null if < 50% matched.
     */
    private fun tryMatchSequence(
        origWords: List<String>,
        flatWords: List<String>,
        offset: Int,
    ): Pair<Int, Int>? {
        if (origWords.isEmpty()) return null
        var origIdx = 0
        var flatIdx = offset
        while (origIdx < origWords.size && flatIdx < flatWords.size) {
            if (origWords[origIdx] == flatWords[flatIdx]) {
                origIdx++
            }
            flatIdx++
        }
        return if (origIdx >= origWords.size / 2) Pair(origIdx, flatIdx) else null
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
