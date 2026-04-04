package by.tigre.speechhelper.domain

/** Режим авто-разметки по абзацам главы. */
enum class AutoMarkupMode {
    /** Только абзацы без голосовых тегов в размеченном тексте. */
    FillMissing,
    /** Все непустые абзацы: исходник для AI — текущий размеченный текст (переразметка). */
    FullRemark,
}
