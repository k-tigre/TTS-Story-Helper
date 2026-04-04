package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.AutoMarkupMode
import by.tigre.speechhelper.domain.TextParser
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object AutoMarkupFingerprint {
    private val utf8 = StandardCharsets.UTF_8

    fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(utf8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

object AutoMarkupParagraphPlanner {

    fun rowsOrSingleFallback(
        listRows: List<StoredChapterParagraph>,
        originalJoined: String,
        markedJoined: String,
    ): List<StoredChapterParagraph> {
        if (listRows.isNotEmpty()) return listRows.sortedBy { it.ordinal }
        if (markedJoined.isBlank()) return emptyList()
        return listOf(StoredChapterParagraph(0, originalJoined.trimEnd(), markedJoined))
    }

    fun paragraphIndicesToProcess(mode: AutoMarkupMode, rows: List<StoredChapterParagraph>): List<Int> =
        rows.indices.filter { i ->
            when (mode) {
                AutoMarkupMode.FillMissing -> paragraphNeedsFill(rows[i])
                AutoMarkupMode.FullRemark -> paragraphHasRunnableContent(rows[i])
            }
        }

    private fun paragraphNeedsFill(r: StoredChapterParagraph): Boolean {
        val t = r.markedText.trim().ifBlank { r.originalText.trim() }
        if (t.isEmpty()) return false
        return !TextParser.hasVoiceMarkers(r.markedText)
    }

    private fun paragraphHasRunnableContent(r: StoredChapterParagraph): Boolean =
        r.markedText.isNotBlank() || r.originalText.isNotBlank()

    fun sourceTextForAi(originalLine: String, markedLine: String, mode: AutoMarkupMode): String =
        when (mode) {
            AutoMarkupMode.FullRemark ->
                markedLine.trim().ifBlank { originalLine.trim() }
            AutoMarkupMode.FillMissing ->
                originalLine.trim().ifBlank { TextParser.stripMarkup(markedLine).trim() }
        }

    /**
     * Разбиение одного абзаца под лимит API (несколько запросов подряд; результаты склеиваются пробелом).
     */
    fun splitParagraphChunks(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val chunks = mutableListOf<String>()
        val sentences = text.split(Regex("""(?<=[.!?])\s+"""))
        val current = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > limit) {
                if (current.isNotBlank()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
                var s = sentence
                while (s.length > limit) {
                    chunks.add(s.take(limit).trim())
                    s = s.drop(limit)
                }
                if (s.isNotBlank()) {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(s)
                }
                continue
            }
            if (current.length + sentence.length + 1 > limit && current.isNotBlank()) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks.ifEmpty { listOf(text) }
    }
}
