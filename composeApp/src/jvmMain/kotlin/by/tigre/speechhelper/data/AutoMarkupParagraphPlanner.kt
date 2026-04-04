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

    /** Непрерывные участки по индексам абзацев в документе: …, 2,3,4 | 7,8 … */
    fun consecutiveIndexRuns(sortedIndices: List<Int>): List<List<Int>> {
        if (sortedIndices.isEmpty()) return emptyList()
        val runs = mutableListOf<MutableList<Int>>()
        var cur = mutableListOf(sortedIndices.first())
        for (k in 1 until sortedIndices.size) {
            val v = sortedIndices[k]
            if (v == cur.last() + 1) cur.add(v)
            else {
                runs.add(cur)
                cur = mutableListOf(v)
            }
        }
        runs.add(cur)
        return runs
    }

    /**
     * Внутри одного непрерывного run — жадно набираем батчи, пока
     * [TextParser.joinParagraphsForStorage] источников не превышает [limit].
     * Один абзац длиннее [limit] попадает в батч из одного индекса (разбивка на запросы — у вызывающего).
     */
    fun greedyBatchesWithinRun(
        runIndices: List<Int>,
        limit: Int,
        originals: List<String>,
        working: List<String>,
        mode: AutoMarkupMode,
    ): List<List<Int>> {
        val batches = mutableListOf<List<Int>>()
        var p = 0
        while (p < runIndices.size) {
            val i = runIndices[p]
            val src0 = sourceTextForAi(originals[i], working[i], mode).trim()
            if (src0.isBlank()) {
                p++
                continue
            }
            if (src0.length > limit) {
                batches.add(listOf(i))
                p++
                continue
            }
            val batch = mutableListOf(i)
            val sources = mutableListOf(src0)
            p++
            while (p < runIndices.size) {
                val j = runIndices[p]
                val sj = sourceTextForAi(originals[j], working[j], mode).trim()
                if (sj.isBlank()) {
                    p++
                    continue
                }
                if (sj.length > limit) break
                val candidate = TextParser.joinParagraphsForStorage(sources + sj)
                if (candidate.length <= limit) {
                    batch.add(j)
                    sources.add(sj)
                    p++
                } else {
                    break
                }
            }
            batches.add(batch)
        }
        return batches
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

    /**
     * Оценка числа HTTP-запросов разметки: жадные пакеты + нарезка одного длинного абзаца на части.
     * [working] — снимок на начало главы (как при реальном планировании батчей).
     */
    fun estimateHttpCallsForChapter(
        indices: List<Int>,
        chunkLimit: Int,
        originals: List<String>,
        working: List<String>,
        mode: AutoMarkupMode,
    ): Int {
        if (indices.isEmpty()) return 0
        val runs = consecutiveIndexRuns(indices.sorted())
        var total = 0
        for (run in runs) {
            var pending = run.toMutableList()
            while (pending.isNotEmpty()) {
                val planned = greedyBatchesWithinRun(pending, chunkLimit, originals, working, mode)
                if (planned.isEmpty()) {
                    pending.removeAt(0)
                    continue
                }
                val batch = planned[0]
                val sources = batch.map { idx ->
                    sourceTextForAi(originals[idx], working[idx], mode).trim()
                }
                val joined = TextParser.joinParagraphsForStorage(sources)
                total += if (batch.size == 1 && joined.length > chunkLimit) {
                    splitParagraphChunks(joined, chunkLimit).size
                } else {
                    1
                }
                pending.removeAll { it in batch.toSet() }
            }
        }
        return total
    }

    /** Число жадных пакетов (без учёта доп. запросов по длинному абзацу). */
    fun estimateGreedyBatchCountForChapter(
        indices: List<Int>,
        chunkLimit: Int,
        originals: List<String>,
        working: List<String>,
        mode: AutoMarkupMode,
    ): Int {
        if (indices.isEmpty()) return 0
        val runs = consecutiveIndexRuns(indices.sorted())
        var count = 0
        for (run in runs) {
            var pending = run.toMutableList()
            while (pending.isNotEmpty()) {
                val planned = greedyBatchesWithinRun(pending, chunkLimit, originals, working, mode)
                if (planned.isEmpty()) {
                    pending.removeAt(0)
                    continue
                }
                count++
                pending.removeAll { it in planned[0].toSet() }
            }
        }
        return count
    }
}
