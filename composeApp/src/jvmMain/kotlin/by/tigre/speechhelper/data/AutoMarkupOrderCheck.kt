package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.TextParser

/**
 * Контроль порядка текста после авторазметки (в т.ч. при нарезке абзаца на несколько запросов).
 * Слова исходника, ушедшего в модель, должны встречаться в ответе в том же порядке (подпоследовательность),
 * допускаются лишние слова в разметке.
 */
object AutoMarkupOrderCheck {

    /** Доля значимых слов исходника, которые должны найтись по порядку в разметке. */
    private const val MIN_ORDER_MATCH_RATIO = 0.88

    /**
     * Ниже полного порога, но достаточно для сохранения с пометкой «нужна ручная правка».
     * Типичный случай: модель чуть переставила границы голосов, текст в целом тот же.
     */
    private const val SOFT_ORDER_MATCH_RATIO = 0.60

    data class Result(
        val ok: Boolean,
        val softOk: Boolean,
        val matchedWordCount: Int,
        val sourceWordCount: Int,
    ) {
        val matchRatio: Double
            get() = if (sourceWordCount == 0) 1.0 else matchedWordCount.toDouble() / sourceWordCount
    }

    fun verify(sourceSentToModel: String, markedModelOutput: String): Result {
        val src = TextParser.extractCompareWords(
            AutoMarkupParagraphAnchors.stripAnchorsFromText(sourceSentToModel),
        )
        if (src.isEmpty()) {
            return Result(ok = true, softOk = true, matchedWordCount = 0, sourceWordCount = 0)
        }
        val out = TextParser.extractCompareWords(
            AutoMarkupParagraphAnchors.stripAnchorsFromText(markedModelOutput),
        )
        var j = 0
        var matched = 0
        for (w in src) {
            while (j < out.size && out[j] != w) j++
            if (j < out.size) {
                matched++
                j++
            }
        }
        val ratio = matched.toDouble() / src.size
        return Result(
            ok = ratio >= MIN_ORDER_MATCH_RATIO,
            softOk = ratio >= SOFT_ORDER_MATCH_RATIO,
            matchedWordCount = matched,
            sourceWordCount = src.size,
        )
    }
}
