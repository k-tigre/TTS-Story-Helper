package by.tigre.speechhelper.domain

import java.text.Normalizer
import java.util.Locale

data class TextSegment(
    val voiceName: String?,
    val text: String,
)

data class ParagraphMapping(
    val originalParagraph: String,
    val matchedSegmentIndices: List<Int>,
    val isValid: Boolean,
    /** Normalized tokens present in markup but not in original (diagnostics). */
    val extraInMarkup: List<String>,
    /** Normalized tokens in original missing from markup (diagnostics). */
    val missingInMarkup: List<String>,
    /** Indices into whitespace-split tokens of stripped original; highlight as missing in markup. */
    val missingOriginalWordIndices: Set<Int> = emptySet(),
    /** Per segment: indices of significant (non-punctuation-only) words extra vs original. */
    val extraMarkupSignificantWordIndicesBySegment: Map<Int, Set<Int>> = emptyMap(),
)

data class ValidationResult(
    val paragraphs: List<ParagraphMapping>,
    val isFullyValid: Boolean,
    val unmatchedSegmentIndices: Set<Int>,
)

object TextParser {

    /** Same pattern as [parse]; exposed so UI can walk raw markup in lockstep with parsed segments. */
    internal val TAG_REGEX = Regex("""\[([^]/]+)](.*?)\[/\1]""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Word boundaries for comparison: Unicode separators (\p{Z}) **and** line breaks (\R),
     * so NBSP / narrow space / перевод строки внутри абзаца не склеивают слова (в отличие от `\s+` в Java).
     */
    private val UNICODE_SPACE_SPLIT = Regex("(?:\\R|\\p{Z})+")

    /** Paragraph boundaries for storage, original/markup sync, and validation (blank line between blocks). */
    private val PARAGRAPH_STORAGE_SPLIT = Regex("\n\\s*\n")

    /** Same split as used in [buildParagraphMapping]; one row per non-blank paragraph. */
    fun splitParagraphsForStorage(text: String): List<String> =
        if (text.isBlank()) emptyList()
        else text.split(PARAGRAPH_STORAGE_SPLIT).map { it.trim() }.filter { it.isNotBlank() }

    fun joinParagraphsForStorage(paragraphs: List<String>): String =
        if (paragraphs.isEmpty()) "" else paragraphs.joinToString("\n\n")

    /**
     * Split plain text into whitespace-separated tokens for comparison and highlight indices.
     * Matches how [stripMarkup] collapses spaces.
     */
    fun splitCompareWhitespace(text: String): List<String> =
        text.split(UNICODE_SPACE_SPLIT).filter { it.isNotBlank() }

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

    private const val SPEECH_VERB_PATTERN =
        """(?:сказал|сказала|проговорил|проговорила|ответил|ответила|прошептал|прошептала|спросил|спросила|пробормотал|пробормотала|воскликнул|воскликнула|крикнул|крикнула|объяснил|объяснила|добавил|добавила|выпалила|выпалил|буркнул|буркнула|произнесла|произнес|произнёс|произнесла|хрипло|тихо|громко|вслух|шепотом)\b"""

    /** Тире-реплика с заглавной после границы фразы: «— Она пошла», не «поняла — и ушла». */
    private val DIALOGUE_OPENING_DASH = Regex("""(?:^|[\n.!?…]\s*)(?:—|–)\s+\p{Lu}\p{L}""")

    /** — реплика, — сказала она (классическое тире-диалог). */
    private val SPEECH_WITH_ATTRIBUTION = Regex(
        """(?:—|–)\s*\p{L}.{0,160}(?:—|–)\s*(?:\p{L}+\s+){0,4}$SPEECH_VERB_PATTERN""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Прямая речь: тире-реплики, «кавычки» с глаголом речи, не одиночное тире в повествовании.
     */
    fun hasDirectSpeech(plainText: String): Boolean {
        val plain = plainText.trim()
        if (plain.isEmpty()) return false
        if (DIALOGUE_OPENING_DASH.containsMatchIn(plain)) return true
        if (SPEECH_WITH_ATTRIBUTION.containsMatchIn(plain)) return true
        if (hasGuillemetSpeechAttribution(plain)) return true
        return false
    }

    private val SPEECH_VERB_LEMMAS = listOf(
        "сказал", "сказала", "проговорил", "проговорила", "ответил", "ответила",
        "прошептал", "прошептала", "спросил", "спросила", "пробормотал", "пробормотала",
        "воскликнул", "воскликнула", "крикнул", "крикнула", "объяснил", "объяснила",
        "добавил", "добавила", "выпалила", "выпалил", "буркнул", "буркнула",
        "произнесла", "произнес", "произнёс", "произнесла",
    )

    private fun hasSpeechVerbNear(fragment: String): Boolean {
        val s = fragment.lowercase()
        return SPEECH_VERB_LEMMAS.any { verb -> s.contains(verb) }
    }

    private fun hasGuillemetSpeechAttribution(plain: String): Boolean {
        val open = plain.indexOf('«')
        val close = plain.indexOf('»', if (open >= 0) open + 1 else 0)
        if (close < 0) return false
        return hasSpeechVerbNear(plain.substring(close + 1).take(64))
    }

    /**
     * Разметка есть, но вся прямая речь осталась в [voice_main] без голоса персонажа.
     */
    fun needsDialogVoiceSplit(markedParagraph: String, originalParagraph: String = ""): Boolean {
        if (!hasVoiceMarkers(markedParagraph)) return false
        if (extractVoiceNames(markedParagraph).any { it != "voice_main" }) return false
        val plain = stripMarkup(markedParagraph).trim().ifBlank { originalParagraph.trim() }
        return hasDirectSpeech(plain)
    }

    fun extractWords(text: String): List<String> {
        return text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
    }

    /**
     * For comparison: NFKC, letters and digits only, lowercased; Russian **ё → е** (often differs after copy-paste).
     * Dashes, quotes, brackets, etc. are stripped from the token.
     */
    fun normalizeCompareToken(token: String): String {
        val nfkc = Normalizer.normalize(token, Normalizer.Form.NFKC)
        val lettersDigits = Regex("[^\\p{L}\\p{N}]+").replace(nfkc, "").lowercase(Locale.ROOT)
        return lettersDigits.replace('ё', 'е')
    }

    /**
     * Strip markup, split by whitespace, keep only tokens that have at least one letter or digit after normalization.
     */
    fun extractCompareWords(text: String): List<String> {
        val stripped = stripMarkup(text)
        return splitCompareWhitespace(stripped)
            .map { normalizeCompareToken(it) }
            .filter { it.isNotEmpty() }
    }

    // ── Markup stripping ────────────────────────────────────────────────────

    private val PAUSE_REGEX = Regex("""<\[[^\]]*\]>""")

    /** SSML-паузы для SpeechKit (`<[small]>`, …) — не часть литературного оригинала. */
    fun stripPauseTags(text: String): String = PAUSE_REGEX.replace(text, "")

    /**
     * Собирает текст «исходника» из разобранных сегментов: убирает паузы и пустые куски,
     * сохраняет переводы строк внутри сегментов (в отличие от [stripMarkup] на целой главе).
     */
    fun joinSegmentTextsAsPlainOriginal(segments: List<TextSegment>): String =
        segments
            .map { stripPauseTags(it.text).trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    fun stripMarkup(text: String): String {
        var result = TAG_REGEX.replace(text) { it.groupValues[2] }
        result = PAUSE_REGEX.replace(result, "")
        return result.replace(UNICODE_SPACE_SPLIT, " ").trim()
    }

    /**
     * For each token from [splitCompareWhitespace]([stripMarkup](paragraph)), finds that token in
     * [paragraph] via left-to-right [String.indexOf], so validation word indices map to raw editor ranges.
     * Returns null if any token is missing (editor text and validation out of sync).
     */
    fun mapCompareTokenRangesInRawParagraph(paragraph: String): List<IntRange>? {
        val strippedForTok = stripMarkup(paragraph)
        val tokens = splitCompareWhitespace(strippedForTok)
        if (tokens.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var searchFrom = 0
        for (tok in tokens) {
            val idx = paragraph.indexOf(tok, searchFrom)
            if (idx < 0) return null
            ranges.add(idx until idx + tok.length)
            searchFrom = idx + tok.length
        }
        return ranges
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

    private data class OrigSigToken(val displayIndex: Int, val normalized: String)
    private data class FlatSigToken(
        val segmentIndex: Int,
        val sigIndexInSegment: Int,
        val normalized: String,
    )

    private fun buildFlatSigList(segments: List<TextSegment>): List<FlatSigToken> {
        val flatTokens = mutableListOf<FlatSigToken>()
        for ((segIdx, seg) in segments.withIndex()) {
            val inner = stripMarkup(seg.text)
            val toks = splitCompareWhitespace(inner)
            var sigIdxInSeg = 0
            for (w in toks) {
                val n = normalizeCompareToken(w)
                if (n.isNotEmpty()) {
                    flatTokens.add(FlatSigToken(segIdx, sigIdxInSeg, n))
                    sigIdxInSeg++
                }
            }
        }
        return flatTokens
    }

    /**
     * Where the first character differs between [oldMarkup] and [newMarkup], or [minOf] length if one is a prefix.
     */
    fun firstMarkupDiffIndex(oldMarkup: String, newMarkup: String): Int? {
        val n = minOf(oldMarkup.length, newMarkup.length)
        for (i in 0 until n) {
            if (oldMarkup[i] != newMarkup[i]) return i
        }
        return if (oldMarkup.length != newMarkup.length) n else null
    }

    /**
     * Index into [parse] segment list for the segment that contains character [charIndex] in [markup].
     */
    fun segmentIndexAtMarkupChar(markup: String, charIndex: Int): Int {
        if (markup.isEmpty()) return 0
        val idx = charIndex.coerceIn(0, markup.lastIndex)
        var last = 0
        var seg = 0
        for (match in TAG_REGEX.findAll(markup)) {
            val beforeEnd = match.range.first
            if (beforeEnd > last) {
                val beforeRaw = markup.substring(last, beforeEnd)
                if (beforeRaw.trim().isNotBlank()) {
                    val range = last until beforeEnd
                    if (idx in range) return seg
                    seg++
                }
            }
            val innerRange = match.groups[2]!!.range
            val innerRaw = markup.substring(innerRange)
            if (idx in match.range) {
                return if (innerRaw.trim().isNotBlank()) seg else seg
            }
            if (innerRaw.trim().isNotBlank()) seg++
            last = match.range.last + 1
        }
        if (last < markup.length) {
            val tail = markup.substring(last)
            if (tail.trim().isNotBlank()) {
                if (idx >= last) return seg
            }
        }
        return seg.coerceAtLeast(0)
    }

    /**
     * First original paragraph index to re-check with [buildParagraphMappingIncremental] (includes one neighbor before).
     */
    fun findIncrementalMarkupValidationStart(
        oldMarkup: String,
        newMarkup: String,
        previous: ValidationResult,
    ): Int {
        val diff = firstMarkupDiffIndex(oldMarkup, newMarkup) ?: return 0
        val segIdx = segmentIndexAtMarkupChar(newMarkup, diff)
        var minP = Int.MAX_VALUE
        for ((pi, p) in previous.paragraphs.withIndex()) {
            for (s in p.matchedSegmentIndices) {
                if (s == segIdx) minP = minOf(minP, pi)
            }
            for (s in p.extraMarkupSignificantWordIndicesBySegment.keys) {
                if (s == segIdx) minP = minOf(minP, pi)
            }
        }
        if (minP == Int.MAX_VALUE && segIdx in previous.unmatchedSegmentIndices) {
            return 0
        }
        if (minP == Int.MAX_VALUE) return 0
        return maxOf(0, minP - 1)
    }

    /**
     * Reuse paragraph mappings before [startParagraphIndex] if original lines unchanged; recompute suffix with LCS.
     */
    fun buildParagraphMappingIncremental(
        originalParagraphs: List<String>,
        segments: List<TextSegment>,
        previous: ValidationResult,
        startParagraphIndex: Int,
    ): ValidationResult {
        if (previous.paragraphs.size != originalParagraphs.size) {
            return buildParagraphMapping(originalParagraphs, segments)
        }
        val start = startParagraphIndex.coerceIn(0, originalParagraphs.size)
        if (start >= originalParagraphs.size) {
            for (i in originalParagraphs.indices) {
                if (originalParagraphs[i] != previous.paragraphs[i].originalParagraph) {
                    return buildParagraphMapping(originalParagraphs, segments)
                }
            }
            val used = previous.paragraphs.flatMap { it.matchedSegmentIndices }.toMutableSet()
            val unmatched = segments.indices.filter { it !in used }.toSet()
            return ValidationResult(
                paragraphs = previous.paragraphs,
                isFullyValid = previous.paragraphs.all { it.isValid } && unmatched.isEmpty(),
                unmatchedSegmentIndices = unmatched,
            )
        }
        for (i in 0 until start) {
            if (originalParagraphs[i] != previous.paragraphs[i].originalParagraph) {
                return buildParagraphMapping(originalParagraphs, segments)
            }
        }
        val flatTokens = buildFlatSigList(segments)
        val flatNorms = flatTokens.map { it.normalized }
        var flatWordPos = flatCursorAfterFirstParagraphs(originalParagraphs, flatNorms, start)
        val newMappings = ArrayList<ParagraphMapping>(originalParagraphs.size)
        for (i in 0 until start) {
            newMappings.add(previous.paragraphs[i])
        }
        val usedSegmentIndices = previous.paragraphs.take(start).flatMap { it.matchedSegmentIndices }.toMutableSet()
        for (i in start until originalParagraphs.size) {
            val (m, newPos) = mapOneOriginalParagraph(
                originalParagraphs[i],
                flatTokens,
                flatNorms,
                flatWordPos,
                usedSegmentIndices,
            )
            flatWordPos = newPos
            newMappings.add(m)
        }
        val unmatchedSegments = segments.indices.filter { it !in usedSegmentIndices }.toSet()
        return ValidationResult(
            paragraphs = newMappings,
            isFullyValid = newMappings.all { it.isValid } && unmatchedSegments.isEmpty(),
            unmatchedSegmentIndices = unmatchedSegments,
        )
    }

    private fun flatCursorAfterFirstParagraphs(
        originalParagraphs: List<String>,
        flatNorms: List<String>,
        paragraphCount: Int,
    ): Int {
        var flatWordPos = 0
        val until = minOf(paragraphCount, originalParagraphs.size)
        for (i in 0 until until) {
            val origPara = originalParagraphs[i]
            val strippedPara = stripMarkup(origPara)
            val displayToks = splitCompareWhitespace(strippedPara)
            val origSignificant = mutableListOf<OrigSigToken>()
            displayToks.forEachIndexed { dispIdx, w ->
                val n = normalizeCompareToken(w)
                if (n.isNotEmpty()) origSignificant.add(OrigSigToken(dispIdx, n))
            }
            if (origSignificant.isEmpty()) continue
            val origSigs = origSignificant.map { it.normalized }
            val matchResult = tryMatchSequence(origSigs, flatNorms, flatWordPos) ?: continue
            flatWordPos = matchResult.second
        }
        return flatWordPos
    }

    private fun mapOneOriginalParagraph(
        origPara: String,
        flatTokens: List<FlatSigToken>,
        flatNorms: List<String>,
        flatWordPos: Int,
        usedSegmentIndices: MutableSet<Int>,
    ): Pair<ParagraphMapping, Int> {
        val strippedPara = stripMarkup(origPara)
        val displayToks = splitCompareWhitespace(strippedPara)
        val origSignificant = mutableListOf<OrigSigToken>()
        displayToks.forEachIndexed { dispIdx, w ->
            val n = normalizeCompareToken(w)
            if (n.isNotEmpty()) origSignificant.add(OrigSigToken(dispIdx, n))
        }

        if (origSignificant.isEmpty()) {
            return ParagraphMapping(
                originalParagraph = origPara,
                matchedSegmentIndices = emptyList(),
                isValid = true,
                extraInMarkup = emptyList(),
                missingInMarkup = emptyList(),
            ) to flatWordPos
        }

        val origSigs = origSignificant.map { it.normalized }
        val matchResult = tryMatchSequence(origSigs, flatNorms, flatWordPos)

        if (matchResult == null) {
            return ParagraphMapping(
                originalParagraph = origPara,
                matchedSegmentIndices = emptyList(),
                isValid = false,
                extraInMarkup = emptyList(),
                missingInMarkup = origSigs,
            ) to flatWordPos
        }

        val (_, consumedUpTo) = matchResult
        val involvedSegments = (flatWordPos until consumedUpTo)
            .map { flatTokens[it].segmentIndex }
            .distinct()
            .sorted()
        usedSegmentIndices.addAll(involvedSegments)

        val markupNormSlice = flatNorms.subList(flatWordPos, consumedUpTo)
        val matchedOrigSig = lcsIndicesA(origSigs, markupNormSlice)
        val matchedMarkupSig = lcsIndicesB(origSigs, markupNormSlice)

        val missing = origSigs.filterIndexed { i, _ -> i !in matchedOrigSig }
        val extra = markupNormSlice.filterIndexed { i, _ -> i !in matchedMarkupSig }

        val missingOriginalWordIndices = origSignificant
            .mapIndexedNotNull { sigRank, t -> if (sigRank !in matchedOrigSig) t.displayIndex else null }
            .toSet()

        val extraMarkupSignificantWordIndicesBySegment = mutableMapOf<Int, MutableSet<Int>>()
        for (i in markupNormSlice.indices) {
            if (i !in matchedMarkupSig) {
                val ft = flatTokens[flatWordPos + i]
                extraMarkupSignificantWordIndicesBySegment
                    .getOrPut(ft.segmentIndex) { mutableSetOf() }
                    .add(ft.sigIndexInSegment)
            }
        }

        val mapping = ParagraphMapping(
            originalParagraph = origPara,
            matchedSegmentIndices = involvedSegments,
            isValid = missing.isEmpty() && extra.isEmpty(),
            extraInMarkup = extra,
            missingInMarkup = missing,
            missingOriginalWordIndices = missingOriginalWordIndices,
            extraMarkupSignificantWordIndicesBySegment = extraMarkupSignificantWordIndicesBySegment,
        )
        val nextPos = if (mapping.isValid || matchedOrigSig.size >= origSigs.size) consumedUpTo else flatWordPos
        return mapping to nextPos
    }

    /**
     * Compare original text (as source of truth) against parsed segments.
     * [originalText] is split with [splitParagraphsForStorage]; prefer
     * [buildParagraphMapping] with a pre-split list when paragraphs are already in storage.
     */
    fun buildParagraphMapping(originalText: String, segments: List<TextSegment>): ValidationResult =
        buildParagraphMapping(splitParagraphsForStorage(originalText), segments)

    /**
     * Same as [buildParagraphMapping] for a full original string, but [originalParagraphs] are
     * already the non-empty (after trim) blocks separated by blank lines (e.g. DB paragraph rows).
     */
    fun buildParagraphMapping(originalParagraphs: List<String>, segments: List<TextSegment>): ValidationResult {
        if (originalParagraphs.isEmpty()) {
            return ValidationResult(
                paragraphs = emptyList(),
                isFullyValid = segments.isEmpty(),
                unmatchedSegmentIndices = segments.indices.toSet(),
            )
        }

        val flatTokens = buildFlatSigList(segments)
        val flatNorms = flatTokens.map { it.normalized }

        val usedSegmentIndices = mutableSetOf<Int>()
        var flatWordPos = 0

        val mappings = originalParagraphs.map { origPara ->
            val (m, newPos) = mapOneOriginalParagraph(
                origPara,
                flatTokens,
                flatNorms,
                flatWordPos,
                usedSegmentIndices,
            )
            flatWordPos = newPos
            m
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

    /**
     * Один абзац оригинала, сопоставленный с сегментом(ами) разметки: для хранения по строкам
     * берём текст оригинала этого абзаца в голосе сегмента (не дублируем соседние абзацы в блоке).
     */
    fun markedRowForOriginalParagraph(
        originalParagraph: String,
        matchedSegments: List<TextSegment>,
    ): String {
        val plain = originalParagraph.trim()
        if (plain.isEmpty()) return ""
        if (matchedSegments.isEmpty()) return plain
        val voices = matchedSegments.mapNotNull { it.voiceName }.toSet()
        return when {
            voices.size == 1 -> {
                val v = voices.single()
                "[$v]\n$plain\n[/$v]"
            }
            matchedSegments.all { it.voiceName == null } -> plain
            else -> plain
        }
    }

    /** После [normalizeMarkupAfterAi] — строки разметки по абзацам оригинала без дублирования текста соседей. */
    fun refreshMarkedRowsForOriginals(
        originals: List<String>,
        chapterMarkup: String,
    ): List<String> {
        if (originals.isEmpty()) return emptyList()
        val trimmedMarkup = chapterMarkup.trim()
        if (trimmedMarkup.isEmpty() || !hasVoiceMarkers(trimmedMarkup)) {
            return originals.map { it.trim() }
        }
        val segments = parse(trimmedMarkup)
        val mappings = buildParagraphMapping(originals, segments).paragraphs
        return originals.indices.map { i ->
            val mapping = mappings.getOrNull(i) ?: return@map originals[i].trim()
            val segs = mapping.matchedSegmentIndices.mapNotNull { idx -> segments.getOrNull(idx) }
            markedRowForOriginalParagraph(originals[i], segs)
        }
    }

    fun paragraphSharesMatchedSegmentsWithPrevious(
        paragraphIndex: Int,
        validation: ValidationResult,
    ): Boolean {
        if (paragraphIndex <= 0) return false
        val cur = validation.paragraphs.getOrNull(paragraphIndex)?.matchedSegmentIndices.orEmpty()
        val prev = validation.paragraphs.getOrNull(paragraphIndex - 1)?.matchedSegmentIndices.orEmpty()
        if (cur.isEmpty() || prev.isEmpty()) return false
        if (cur == prev) return true
        // Один сегмент на два абзаца: у следующего только хвост уже показанного блока.
        val prevLast = prev.last()
        return cur.first() == prevLast && cur.all { it >= prevLast }
    }

    /**
     * Индексы сегментов главы для строки «Разбивка»: сначала по разметке абзаца (точнее после
     * авторазметки), иначе — из [ValidationResult].
     */
    fun displaySegmentIndicesForParagraph(
        paragraphIndex: Int,
        markedParagraph: String,
        validation: ValidationResult?,
        allSegments: List<TextSegment>,
        searchStartIndex: Int,
    ): List<Int> {
        if (hasVoiceMarkers(markedParagraph)) {
            val local = parse(markedParagraph)
            if (local.isNotEmpty()) {
                var cursor = searchStartIndex
                val indices = mutableListOf<Int>()
                for (piece in local) {
                    val idx = allSegments.indexOfFirstFrom(cursor) { global ->
                        global.voiceName == piece.voiceName &&
                            segmentTextsEquivalent(global.text, piece.text)
                    }
                    if (idx < 0) break
                    indices.add(idx)
                    cursor = idx + 1
                }
                if (indices.isNotEmpty()) return indices
            }
        }
        return validation?.paragraphs?.getOrNull(paragraphIndex)?.matchedSegmentIndices.orEmpty()
    }

    private fun List<TextSegment>.indexOfFirstFrom(
        start: Int,
        predicate: (TextSegment) -> Boolean,
    ): Int {
        for (i in start.coerceAtLeast(0) until size) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    private fun segmentTextsEquivalent(a: String, b: String): Boolean {
        val na = normalizedSignificantTokens(stripMarkup(a))
        val nb = normalizedSignificantTokens(stripMarkup(b))
        return na.isNotEmpty() && na == nb
    }

    private fun normalizedSignificantTokens(text: String): List<String> =
        splitCompareWhitespace(text)
            .map { normalizeCompareToken(it) }
            .filter { it.isNotEmpty() }

    /**
     * После авто-разметки: фрагменты только с паузами `<[…]>` между блоками `[voice]…[/voice]`
     * оказываются без голоса и дают «тихую» паузу в TTS — переносим их внутрь соседнего голоса
     * (предпочтительно следующего). Затем склеиваем подряд идущие сегменты с одним именем голоса.
     */
    fun normalizeMarkupAfterAi(input: String): String {
        if (input.isBlank()) return input
        val segs = parse(input).toMutableList()

        var i = 0
        while (i < segs.size) {
            val s = segs[i]
            if (s.voiceName != null || !isUnvoicedPauseOrWhitespaceOnly(s.text)) {
                i++
                continue
            }
            val mergedIntoNext = i + 1 < segs.size && segs[i + 1].voiceName != null
            val mergedIntoPrev = i > 0 && segs[i - 1].voiceName != null
            when {
                mergedIntoNext -> {
                    val n = segs[i + 1]
                    val p = s.text.trim()
                    segs[i + 1] = n.copy(
                        text = when {
                            p.isEmpty() -> n.text.trim()
                            n.text.isBlank() -> p
                            else -> "$p\n\n${n.text.trim()}"
                        },
                    )
                    segs.removeAt(i)
                }
                mergedIntoPrev -> {
                    val p = segs[i - 1]
                    val t = s.text.trim()
                    segs[i - 1] = p.copy(
                        text = when {
                            t.isEmpty() -> p.text.trim()
                            p.text.isBlank() -> t
                            else -> "${p.text.trim()}\n\n$t"
                        },
                    )
                    segs.removeAt(i)
                }
                else -> i++
            }
        }

        if (segs.isEmpty()) return input.trim()

        val mergedVoices = mutableListOf<TextSegment>()
        for (seg in segs) {
            val last = mergedVoices.lastOrNull()
            if (last != null && last.voiceName != null && seg.voiceName == last.voiceName &&
                shouldMergeSameVoiceSegments(last.text, seg.text)
            ) {
                val a = last.text.trim()
                val b = seg.text.trim()
                mergedVoices[mergedVoices.lastIndex] = last.copy(
                    text = when {
                        a.isEmpty() -> b
                        b.isEmpty() -> a
                        else -> "$a\n\n$b"
                    },
                )
            } else {
                mergedVoices.add(seg)
            }
        }
        return buildText(mergedVoices)
    }

    /**
     * Не склеиваем два полноценных блока повествования: иначе соседние абзацы одним голосом
     * сливаются в один сегмент и ломают сопоставление в «Разбивке».
     */
    private fun shouldMergeSameVoiceSegments(left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        if (a.isEmpty() || b.isEmpty()) return true
        if (a.contains("\n\n") || b.contains("\n\n")) return false
        val wordsA = normalizedSignificantTokens(a).size
        val wordsB = normalizedSignificantTokens(b).size
        return wordsA < 8 || wordsB < 8
    }

    /** Сегмент без голоса, в котором после снятия разметки не остаётся текста (только паузы и пробелы). */
    private fun isUnvoicedPauseOrWhitespaceOnly(raw: String): Boolean {
        if (raw.isBlank()) return true
        return stripMarkup(raw).isBlank()
    }
}
