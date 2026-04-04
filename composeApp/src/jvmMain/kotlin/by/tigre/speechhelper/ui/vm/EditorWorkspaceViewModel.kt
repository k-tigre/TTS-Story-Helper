package by.tigre.speechhelper.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import by.tigre.speechhelper.data.InitialSessionSnapshot
import by.tigre.speechhelper.data.ChapterContentSnapshot
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.TextSegment
import by.tigre.speechhelper.domain.ValidationResult
import by.tigre.speechhelper.domain.VoiceSettings

/** Фильтр списка сегментов в режиме «Разбивка». */
sealed class SegmentViewVoiceFilter {
    data object All : SegmentViewVoiceFilter()
    data class Only(val voiceName: String) : SegmentViewVoiceFilter()
    data object Unvoiced : SegmentViewVoiceFilter()
}

class EditorWorkspaceViewModel(
    private val currentChapterId: () -> String,
) {

    var text by mutableStateOf("")
    var originalText by mutableStateOf("")

    var selectedVoice by mutableStateOf(by.tigre.speechhelper.domain.API_VOICES[0])
    var selectedFormat by mutableStateOf(by.tigre.speechhelper.domain.FORMATS[0])
    var speed by mutableStateOf(1.0)
    var pitchShift by mutableStateOf(0.0)
    var selectedRole by mutableStateOf("")

    var viewMode by mutableStateOf(0)
    var markupModeEnabled by mutableStateOf(true)
    private var textHadOriginalMarkup = false
    val segments = mutableStateListOf<TextSegment>()

    var segmentViewVoiceFilter by mutableStateOf<SegmentViewVoiceFilter>(SegmentViewVoiceFilter.All)

    val voiceMapping = mutableStateMapOf<String, VoiceSettings>()

    var validationResult by mutableStateOf<ValidationResult?>(null)
        private set

    val hasMarkers: Boolean get() = TextParser.hasVoiceMarkers(text)
    val detectedVoices: Set<String> get() = if (hasMarkers) TextParser.extractVoiceNames(text) else emptySet()

    fun hydrateFromInitialSnapshot(snap: InitialSessionSnapshot) {
        text = snap.chapterText
        originalText = snap.originalText
        voiceMapping.clear()
        voiceMapping.putAll(snap.voiceMapping)
        ensureVoiceMain()
        revalidate()
    }

    fun applyChapterSwitch(snap: ChapterContentSnapshot) {
        text = snap.markedJoined
        originalText = snap.originalJoined
        markupModeEnabled = true
        segmentViewVoiceFilter = SegmentViewVoiceFilter.All
        revalidate(preloadedOriginalParagraphs = snap.originalParagraphs)
    }

    fun applyAfterImport(snap: ChapterContentSnapshot) {
        voiceMapping.clear()
        text = snap.markedJoined
        originalText = snap.originalJoined
        segmentViewVoiceFilter = SegmentViewVoiceFilter.All
        markupModeEnabled = true
        ensureVoiceMain()
        revalidate(preloadedOriginalParagraphs = snap.originalParagraphs)
    }

    fun loadChapterContentFromStorage(newChapterId: String) {
        text = SessionStorage.getChapterText(newChapterId)
        originalText = SessionStorage.getOriginalText(newChapterId)
        segmentViewVoiceFilter = SegmentViewVoiceFilter.All
        markupModeEnabled = true
        ensureVoiceMain()
        revalidate()
    }

    fun resetSessionAfterClearAll(
        chapterText: String,
        original: String,
        voiceFromStorage: Map<String, VoiceSettings>,
    ) {
        text = chapterText
        originalText = original
        voiceMapping.clear()
        voiceMapping.putAll(voiceFromStorage)
        segmentViewVoiceFilter = SegmentViewVoiceFilter.All
        markupModeEnabled = true
        ensureVoiceMain()
        revalidate()
    }

    fun resetVoiceMappingFromStorage(map: Map<String, VoiceSettings>) {
        voiceMapping.clear()
        voiceMapping.putAll(map)
        ensureVoiceMain()
        revalidate()
    }

    /**
     * @param preloadedOriginalParagraphs если передан (например после [SessionStorage.persistSwitchChapter]),
     * не дергаем SQLite повторно для оригинальных абзацев.
     */
    fun revalidate(preloadedOriginalParagraphs: List<String>? = null) {
        validationResult = if (originalText.isNotBlank() && hasMarkers) {
            val segs = TextParser.parse(text)
            segments.clear()
            segments.addAll(segs)
            val origParagraphs = preloadedOriginalParagraphs ?: originalParagraphsForValidation()
            TextParser.buildParagraphMapping(origParagraphs, segs)
        } else {
            null
        }
    }

    private fun originalParagraphsForValidation(): List<String> {
        val id = currentChapterId()
        val persisted = SessionStorage.getOriginalText(id)
        if (persisted != originalText) {
            return TextParser.splitParagraphsForStorage(originalText)
        }
        val fromDb = SessionStorage.listChapterParagraphs(id)
            .sortedBy { it.ordinal }
            .map { it.originalText }
        if (fromDb.isEmpty() && originalText.isNotBlank()) {
            return TextParser.splitParagraphsForStorage(originalText)
        }
        return fromDb
    }

    fun ensureVoiceMain() {
        if ("voice_main" !in voiceMapping) {
            voiceMapping["voice_main"] = VoiceSettings()
        }
    }

    fun saveVoiceMapping() {
        SessionStorage.voiceMapping = voiceMapping.toMap()
    }

    fun ensureVoiceMappings(voices: Set<String>) {
        for (name in voices) {
            if (name !in voiceMapping) {
                voiceMapping[name] = VoiceSettings()
            }
        }
    }

    fun removeUnusedVoices() {
        val unused = voiceMapping.keys - detectedVoices
        for (name in unused) voiceMapping.remove(name)
        saveVoiceMapping()
    }

    fun removeUnusedVoicesWithMessage(): Pair<Boolean, String> {
        val unused = voiceMapping.keys - detectedVoices
        for (name in unused) voiceMapping.remove(name)
        saveVoiceMapping()
        val msg = if (unused.isEmpty()) "Нет неиспользуемых голосов" else "Удалено голосов: ${unused.size}"
        return unused.isNotEmpty() to msg
    }

    fun mergeVoice(fromName: String, toName: String): String {
        fun applyVoiceRename(raw: String): String =
            raw.replace("[$fromName]", "[$toName]").replace("[/$fromName]", "[/$toName]")

        text = applyVoiceRename(text)
        SessionStorage.setChapterText(currentChapterId(), text)
        for (ch in SessionStorage.listChapters()) {
            if (ch.id == currentChapterId()) continue
            val t = SessionStorage.getChapterText(ch.id)
            val updated = applyVoiceRename(t)
            if (updated != t) SessionStorage.setChapterText(ch.id, updated)
        }
        if (viewMode == 1) {
            for (i in segments.indices) {
                if (segments[i].voiceName == fromName) {
                    segments[i] = segments[i].copy(voiceName = toName)
                }
            }
        }
        val voiceFilter = segmentViewVoiceFilter
        if (voiceFilter is SegmentViewVoiceFilter.Only && voiceFilter.voiceName == fromName) {
            segmentViewVoiceFilter = SegmentViewVoiceFilter.Only(toName)
        }
        voiceMapping.remove(fromName)
        if (toName !in voiceMapping) voiceMapping[toName] = VoiceSettings()
        saveVoiceMapping()
        return "Голос \"$fromName\" объединён с \"$toName\" во всех главах"
    }

    fun syncTextFromSegments() {
        text = TextParser.buildText(segments.toList())
    }

    fun mergeSegmentWithPrevious(index: Int): String? {
        if (index <= 0 || index >= segments.size) return null
        val prev = segments[index - 1]
        val curr = segments[index]
        segments[index - 1] = prev.copy(text = joinMergedSegmentTexts(prev.text, curr.text))
        segments.removeAt(index)
        syncTextFromSegments()
        SessionStorage.setChapterText(currentChapterId(), text)
        revalidate()
        return "Сегмент объединён с предыдущим"
    }

    fun mergeSegmentWithNext(index: Int): String? {
        if (index < 0 || index >= segments.size - 1) return null
        val curr = segments[index]
        val next = segments[index + 1]
        segments[index] = curr.copy(text = joinMergedSegmentTexts(curr.text, next.text))
        segments.removeAt(index + 1)
        syncTextFromSegments()
        SessionStorage.setChapterText(currentChapterId(), text)
        revalidate()
        return "Сегмент объединён со следующим"
    }

    fun resetMarkup() {
        text = if (originalText.isNotBlank()) originalText
        else TextParser.parse(text).joinToString("\n\n") { it.text }
        SessionStorage.setChapterText(currentChapterId(), text)
        markupModeEnabled = true
        viewMode = 0
        validationResult = null
    }

    fun syncSegmentsFromText(previousMarkupForIncremental: String? = null) {
        val cachedParas = originalParagraphsForValidation()
        val oldValidation = validationResult
        val newSegs = TextParser.parse(text)
        segments.clear()
        segments.addAll(newSegs)
        val id = currentChapterId()
        if (originalText.isBlank() && hasMarkers) {
            originalText = segments.joinToString("\n\n") { it.text }
            SessionStorage.setOriginalText(id, originalText)
        }
        validationResult = when {
            originalText.isBlank() || !hasMarkers -> null
            previousMarkupForIncremental != null &&
                oldValidation != null &&
                previousMarkupForIncremental != text &&
                cachedParas.size == oldValidation.paragraphs.size -> {
                val start = TextParser.findIncrementalMarkupValidationStart(
                    previousMarkupForIncremental,
                    text,
                    oldValidation,
                )
                TextParser.buildParagraphMappingIncremental(
                    cachedParas,
                    newSegs,
                    oldValidation,
                    start,
                )
            }
            else -> TextParser.buildParagraphMapping(cachedParas, newSegs)
        }
    }

    fun wrapTextAsMarkup() {
        if (text.isBlank() || hasMarkers) return
        val id = currentChapterId()
        originalText = text
        SessionStorage.setOriginalText(id, originalText)
        text = "[voice_main]\n$text\n[/voice_main]"
        markupModeEnabled = true
        textHadOriginalMarkup = false
        revalidate()
    }

    fun enableMarkupMode() {
        if (!hasMarkers) return
        val id = currentChapterId()
        if (originalText.isBlank()) {
            originalText = text
            SessionStorage.setOriginalText(id, originalText)
        }
        markupModeEnabled = true
        textHadOriginalMarkup = true
        revalidate()
    }

    fun unwrapMarkup() {
        val id = currentChapterId()
        if (!textHadOriginalMarkup) {
            text = if (originalText.isNotBlank()) originalText
            else TextParser.parse(text).joinToString("\n\n") { it.text }
            originalText = ""
            SessionStorage.setOriginalText(id, "")
            SessionStorage.setChapterText(id, text)
        }
        markupModeEnabled = true
        viewMode = 0
        validationResult = null
    }

    fun updateOriginalText(newOriginal: String) {
        val oldOriginal = originalText
        originalText = newOriginal
        val id = currentChapterId()
        SessionStorage.setOriginalText(id, newOriginal)
        text = syncOriginalToMarkup(oldOriginal, newOriginal, text)
        revalidate()
    }

    private fun syncOriginalToMarkup(oldOriginal: String, newOriginal: String, markup: String): String {
        val oldParagraphs = TextParser.splitParagraphsForStorage(oldOriginal)
        val newParagraphs = TextParser.splitParagraphsForStorage(newOriginal)
        var result = markup
        for (i in oldParagraphs.indices) {
            if (i >= newParagraphs.size) break
            if (oldParagraphs[i] != newParagraphs[i]) {
                result = result.replace(oldParagraphs[i], newParagraphs[i])
            }
        }
        if (newParagraphs.size > oldParagraphs.size) {
            val extra = newParagraphs.drop(oldParagraphs.size).joinToString("\n\n")
            val lastCloseTag = result.lastIndexOf("[/")
            if (lastCloseTag >= 0) {
                result = result.substring(0, lastCloseTag) + extra + "\n" + result.substring(lastCloseTag)
            }
        }
        return result
    }
}

private fun joinMergedSegmentTexts(a: String, b: String): String {
    val x = a.trim()
    val y = b.trim()
    return when {
        x.isEmpty() -> y
        y.isEmpty() -> x
        else -> "$x\n\n$y"
    }
}
