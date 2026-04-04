package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.TextParser
import kotlin.math.max
import kotlin.math.min

/**
 * Делит размеченный ответ пакетного запроса на столько же блоков, сколько исходных абзацев.
 * Цель — только грубое сопоставление по порядку и объёму; правка текста идёт через обычную валидацию.
 */
object AutoMarkupBatchAlign {

    /**
     * @param sources те же строки, что ушли в API (по порядку батча)
     * @param markedJoined сырой ответ модели
     */
    fun alignOrNull(sources: List<String>, markedJoined: String): List<String>? {
        val n = sources.size
        when {
            n == 0 -> return emptyList()
            markedJoined.isBlank() -> return null
            n == 1 -> return listOf(markedJoined.trim())
        }

        val outs = TextParser.splitParagraphsForStorage(markedJoined)
        val m = outs.size
        if (m == 0) return null

        if (m < n) return null

        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.NEGATIVE_INFINITY } }
        val cut = Array(n + 1) { IntArray(m + 1) { -1 } }
        dp[0][0] = 0.0

        for (i in 1..n) {
            for (j in i..m) {
                var best = Double.NEGATIVE_INFINITY
                var bestK = -1
                for (k in (i - 1) until j) {
                    val base = dp[i - 1][k]
                    if (base == Double.NEGATIVE_INFINITY) continue
                    val mergedMarked = outs.subList(k, j).joinToString("\n\n")
                    val sc = softPartitionScore(sources[i - 1], mergedMarked)
                    val sum = base + sc
                    if (sum > best) {
                        best = sum
                        bestK = k
                    }
                }
                dp[i][j] = best
                cut[i][j] = bestK
            }
        }

        if (dp[n][m] != Double.NEGATIVE_INFINITY) {
            val fromDp = reconstructDp(cut, outs, n, m)
            if (fromDp != null) return fromDp
        }
        return proportionalAlign(sources, outs)
    }

    private fun reconstructDp(
        cut: Array<IntArray>,
        outs: List<String>,
        n: Int,
        m: Int,
    ): List<String>? {
        val result = Array(n) { "" }
        var j = m
        var i = n
        while (i > 0) {
            val k = cut[i][j]
            if (k < 0) return null
            result[i - 1] = outs.subList(k, j).joinToString("\n\n").trim()
            j = k
            i--
        }
        return result.toList()
    }

    /**
     * Запасной вариант: границы между фрагментами ответа по относительным длинам исходников (без лексики).
     */
    internal fun proportionalAlign(sources: List<String>, outs: List<String>): List<String> {
        val n = sources.size
        val m = outs.size
        val weights = sources.map {
            TextParser.stripMarkup(it).trim().length.coerceAtLeast(1)
        }
        val cuts = monotonicLengthCuts(m, n, weights)
        return (0 until n).map { idx ->
            outs.subList(cuts[idx], cuts[idx + 1]).joinToString("\n\n").trim()
        }
    }

    /** Распределить [m] кусков по [n] группам: у каждой группы хотя бы 1, доли по [weights]. */
    private fun monotonicLengthCuts(m: Int, n: Int, weights: List<Int>): IntArray {
        val w = weights.map { it.coerceAtLeast(1) }
        val alloc = IntArray(n) { 1 }
        var rem = m - n
        if (rem < 0) {
            for (i in 0 until n) alloc[i] = 0
            for (i in 0 until min(m, n)) alloc[i] = 1
            return cumulativeCuts(alloc)
        }
        val sum = w.sum().toDouble()
        val exact = w.map { rem * it / sum }
        val floors = exact.map { it.toInt() }
        for (i in 0 until n) alloc[i] += floors[i]
        var left = rem - floors.sum()
        val frac = exact.mapIndexed { i, e -> i to (e - floors[i]) }
        val order = frac.sortedByDescending { it.second }.map { it.first }
        var o = 0
        while (left > 0) {
            alloc[order[o % order.size]]++
            left--
            o++
        }
        return cumulativeCuts(alloc)
    }

    private fun cumulativeCuts(alloc: IntArray): IntArray {
        val cuts = IntArray(alloc.size + 1)
        var c = 0
        for (i in alloc.indices) {
            cuts[i] = c
            c += alloc[i]
        }
        cuts[alloc.size] = c
        return cuts
    }

    /**
     * Мягкая оценка для DP: устойчивая к опечаткам и перефразированию в ответе;
     * не используется как жёсткий порог — только для выбора лучшего разбиения.
     */
    private fun softPartitionScore(sourceSent: String, markedOut: String): Double {
        val s = TextParser.stripMarkup(sourceSent).trim()
        val t = TextParser.stripMarkup(markedOut).trim()
        if (s.isEmpty() && t.isEmpty()) return 1.0
        if (s.isEmpty() || t.isEmpty()) return 0.18

        val lenSim = min(s.length, t.length).toDouble() / max(s.length, t.length)

        val ws = TextParser.extractCompareWords(s)
        val wt = TextParser.extractCompareWords(t)
        val orderHit = orderedHitRatio(ws, wt)
        val charDice = charMultisetDice(s, t)
        val lcsRatio =
            if (ws.isEmpty() && wt.isEmpty()) 1.0
            else lcsWordLen(ws, wt).toDouble() / max(ws.size, wt.size)

        return 0.22 * lenSim + 0.28 * orderHit + 0.28 * charDice + 0.22 * lcsRatio
    }

    /** Доля слов исходника, которые встречаются в том же порядке в ответе (не подряд). */
    private fun orderedHitRatio(ws: List<String>, wt: List<String>): Double {
        if (ws.isEmpty()) return if (wt.isEmpty()) 1.0 else 0.4
        var j = 0
        var hit = 0
        for (w in ws) {
            while (j < wt.size && wt[j] != w) j++
            if (j < wt.size) {
                hit++
                j++
            }
        }
        return hit.toDouble() / ws.size
    }

    private fun charMultisetDice(a: String, b: String): Double {
        val fa = mutableMapOf<Char, Int>()
        val fb = mutableMapOf<Char, Int>()
        for (ch in a.lowercase()) {
            if (ch.isLetterOrDigit()) fa[ch] = fa.getOrDefault(ch, 0) + 1
        }
        for (ch in b.lowercase()) {
            if (ch.isLetterOrDigit()) fb[ch] = fb.getOrDefault(ch, 0) + 1
        }
        val sumA = fa.values.sum()
        val sumB = fb.values.sum()
        if (sumA + sumB == 0) return lenOnlyFallback(a, b)
        var inter = 0
        for ((c, ca) in fa) inter += min(ca, fb[c] ?: 0)
        return 2.0 * inter / (sumA + sumB)
    }

    private fun lenOnlyFallback(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.2
        return min(a.length, b.length).toDouble() / max(a.length, b.length)
    }

    private fun lcsWordLen(a: List<String>, b: List<String>): Int {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return 0
        val dp = Array(2) { IntArray(m + 1) }
        for (i in 1..n) {
            val cur = i and 1
            val prev = cur xor 1
            for (j in 1..m) {
                dp[cur][j] = if (a[i - 1] == b[j - 1]) dp[prev][j - 1] + 1 else max(dp[prev][j], dp[cur][j - 1])
            }
        }
        return dp[n and 1][m]
    }
}
