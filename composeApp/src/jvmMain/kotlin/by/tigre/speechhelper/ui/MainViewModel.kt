package by.tigre.speechhelper.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.AiMarkupApi
import by.tigre.speechhelper.data.ChapterAudioPlayer
import by.tigre.speechhelper.data.EpubParser
import by.tigre.speechhelper.data.Fb2Parser
import by.tigre.speechhelper.data.ParsedBook
import by.tigre.speechhelper.data.MarkupResult
import by.tigre.speechhelper.data.LocalTtsApi
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.data.SpeechSynthesizer
import by.tigre.speechhelper.data.SynthesisResult
import by.tigre.speechhelper.data.WavMerge
import by.tigre.speechhelper.domain.API_VOICES
import by.tigre.speechhelper.domain.FORMATS
import by.tigre.speechhelper.domain.LocalTtsSettings
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.TextSegment
import by.tigre.speechhelper.domain.ValidationResult
import by.tigre.speechhelper.domain.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

class MainViewModel(private val scope: CoroutineScope) {

    // Chapter management
    var chapters by mutableStateOf(SessionStorage.listChapters())
        private set
    var currentChapterId by mutableStateOf(SessionStorage.ensureCurrentChapter())
        private set
    var currentBookName by mutableStateOf(SessionStorage.currentBookName)
        private set

    // Chapter text
    var text by mutableStateOf(SessionStorage.getChapterText(currentChapterId))
    var originalText by mutableStateOf(SessionStorage.getOriginalText(currentChapterId))

    // Simple synthesis settings
    var selectedVoice by mutableStateOf(API_VOICES[0])
    var selectedFormat by mutableStateOf(FORMATS[0])
    var speed by mutableStateOf(1.0)
    var pitchShift by mutableStateOf(0.0)
    var selectedRole by mutableStateOf("")

    var synthesisBackend by mutableStateOf(SessionStorage.synthesisBackend)
    var localTtsSettings by mutableStateOf(SessionStorage.localTtsSettings)

    // Loading/progress
    var isLoading by mutableStateOf(false)
        private set
    var progressMessage by mutableStateOf("")
        private set
    var statusMessage by mutableStateOf("")

    // Audio path
    var chapterAudioPath by mutableStateOf<String?>(SessionStorage.getChapterAudioPath(currentChapterId))
        private set

    // Player state (set from both ViewModel and composable LaunchedEffects)
    var playerIsPlaying by mutableStateOf(false)
    var playerPositionMs by mutableStateOf(0L)
    var playerDurationMs by mutableStateOf(0L)
    var playerReady by mutableStateOf(false)
    val chapterPlayer = ChapterAudioPlayer()

    // Voice mapping
    val voiceMapping = mutableStateMapOf<String, VoiceSettings>()

    // View mode: 0 = Text, 1 = Segments
    var viewMode by mutableStateOf(0)
    var markupModeEnabled by mutableStateOf(false)
    private var textHadOriginalMarkup = false
    val segments = mutableStateListOf<TextSegment>()

    // Dialog visibility
    var showCreateDialog by mutableStateOf(false)
    var showRenameDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    var showClearAllDialog by mutableStateOf(false)
    var showSaveBookDialog by mutableStateOf(false)
    var showLoadBookDialog by mutableStateOf(false)
    var showFolderIdDialog by mutableStateOf(false)
    var showResetMarkupDialog by mutableStateOf(false)
    var showHelpDialog by mutableStateOf(false)

    val hasMarkers: Boolean get() = TextParser.hasVoiceMarkers(text)
    val detectedVoices: Set<String> get() = if (hasMarkers) TextParser.extractVoiceNames(text) else emptySet()

    // Paragraph validation
    var validationResult by mutableStateOf<ValidationResult?>(null)
        private set

    fun revalidate() {
        validationResult = if (originalText.isNotBlank() && hasMarkers) {
            val segs = TextParser.parse(text)
            segments.clear()
            segments.addAll(segs)
            TextParser.buildParagraphMapping(originalText, segs)
        } else {
            null
        }
    }

    init {
        voiceMapping.putAll(SessionStorage.voiceMapping)
        ensureVoiceMain()
    }

    private fun ensureVoiceMain() {
        if ("voice_main" !in voiceMapping) {
            voiceMapping["voice_main"] = VoiceSettings()
        }
    }

    fun onSynthesisBackendChange(backend: SynthesisBackend) {
        synthesisBackend = backend
        SessionStorage.synthesisBackend = backend
    }

    fun onLocalTtsSettingsChange(settings: LocalTtsSettings) {
        localTtsSettings = settings
        SessionStorage.localTtsSettings = settings
    }

    fun checkLocalTtsConnection() {
        scope.launch {
            val ok = LocalTtsApi.checkHealth(localTtsSettings.baseUrl)
            statusMessage = if (ok) {
                "Локальный TTS: сервер отвечает"
            } else {
                "Локальный TTS: нет ответа (запустите сервер из каталога local-tts-server)"
            }
        }
    }

    fun synthesizeAudio(text: String, settings: VoiceSettings, format: String): Flow<SynthesisResult> =
        SpeechSynthesizer.synthesize(
            text = text,
            voiceSettings = settings,
            outputFormat = format,
            backend = synthesisBackend,
            localSettings = localTtsSettings,
            cloudToken = TokenStorage.iamToken,
        )

    // ── Chapter management ────────────────────────────────────────────────────

    fun saveCurrentChapter() {
        SessionStorage.setChapterText(currentChapterId, text)
    }

    fun switchToChapter(id: String) {
        if (id == currentChapterId) return
        saveCurrentChapter()
        chapterPlayer.close()
        playerIsPlaying = false
        currentChapterId = id
        SessionStorage.currentChapterId = id
        text = SessionStorage.getChapterText(id)
        originalText = SessionStorage.getOriginalText(id)
        chapterAudioPath = SessionStorage.getChapterAudioPath(id)
        statusMessage = ""
        markupModeEnabled = originalText.isNotBlank() && hasMarkers
        ensureVoiceMain()
        revalidate()
    }

    fun createChapter(name: String) {
        saveCurrentChapter()
        val id = SessionStorage.createChapter(name)
        chapters = SessionStorage.listChapters()
        switchToChapter(id)
        showCreateDialog = false
    }

    fun renameCurrentChapter(name: String) {
        SessionStorage.renameChapter(currentChapterId, name)
        chapters = SessionStorage.listChapters()
        showRenameDialog = false
    }

    fun deleteCurrentChapter() {
        SessionStorage.deleteChapter(currentChapterId)
        chapters = SessionStorage.listChapters()
        val newId = SessionStorage.ensureCurrentChapter()
        currentChapterId = newId
        text = SessionStorage.getChapterText(newId)
        originalText = SessionStorage.getOriginalText(newId)
        chapterAudioPath = SessionStorage.getChapterAudioPath(newId)
        showDeleteDialog = false
    }

    fun clearAll() {
        saveCurrentChapter()
        SessionStorage.clearAllData()
        val id = SessionStorage.ensureCurrentChapter()
        chapters = SessionStorage.listChapters()
        currentChapterId = id
        text = SessionStorage.getChapterText(id)
        originalText = SessionStorage.getOriginalText(id)
        chapterAudioPath = null
        voiceMapping.clear()
        currentBookName = ""
        synthesisBackend = SessionStorage.synthesisBackend
        localTtsSettings = SessionStorage.localTtsSettings
        statusMessage = "Всё очищено"
        showClearAllDialog = false
    }

    // ── Book management ───────────────────────────────────────────────────────

    fun saveCurrentBook() {
        if (currentBookName.isNotBlank()) {
            saveCurrentChapter()
            saveVoiceMapping()
            SessionStorage.saveBook(currentBookName)
            statusMessage = "Книга \"$currentBookName\" сохранена"
        } else {
            showSaveBookDialog = true
        }
    }

    fun saveBook(bookName: String) {
        saveCurrentChapter()
        saveVoiceMapping()
        SessionStorage.saveBook(bookName)
        currentBookName = bookName
        SessionStorage.currentBookName = bookName
        statusMessage = "Книга \"$bookName\" сохранена"
        showSaveBookDialog = false
    }

    fun loadBook(bookName: String) {
        saveCurrentChapter()
        if (SessionStorage.loadBook(bookName)) {
            chapters = SessionStorage.listChapters()
            val id = SessionStorage.ensureCurrentChapter()
            currentChapterId = id
            text = SessionStorage.getChapterText(id)
            originalText = SessionStorage.getOriginalText(id)
            chapterAudioPath = SessionStorage.getChapterAudioPath(id)
            voiceMapping.clear()
            voiceMapping.putAll(SessionStorage.voiceMapping)
            currentBookName = bookName
            SessionStorage.currentBookName = bookName
            statusMessage = "Книга \"$bookName\" загружена"
        } else {
            statusMessage = "Ошибка: не удалось загрузить книгу"
        }
        showLoadBookDialog = false
    }

    fun importFb2() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Выбрать FB2 файл"
                    fileFilter = FileNameExtensionFilter("FictionBook 2 (*.fb2)", "fb2")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
            if (file == null) return@launch
            importParsedBook(label = "FB2") { Fb2Parser.parse(file) }
        }
    }

    fun importEpub() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Выбрать EPUB файл"
                    fileFilter = FileNameExtensionFilter("EPUB (*.epub)", "epub")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
            if (file == null) return@launch
            importParsedBook(label = "EPUB") { EpubParser.parse(file) }
        }
    }

    private suspend fun importParsedBook(label: String, parse: suspend () -> ParsedBook) {
        isLoading = true
        progressMessage = "Чтение $label..."
        try {
            val book = withContext(Dispatchers.IO) { parse() }
            if (book.chapters.isEmpty()) {
                statusMessage = "Ошибка: не найдены главы в файле"
                return
            }

            saveCurrentChapter()
            withContext(Dispatchers.IO) { SessionStorage.clearAllData() }

            var firstId: String? = null
            book.chapters.forEachIndexed { index, chapter ->
                progressMessage = "Создание главы ${index + 1} из ${book.chapters.size}..."
                val id = withContext(Dispatchers.IO) {
                    val id = SessionStorage.createChapter(chapter.name)
                    SessionStorage.setChapterText(id, chapter.text)
                    id
                }
                if (firstId == null) firstId = id
            }

            chapters = SessionStorage.listChapters()
            val id = firstId ?: SessionStorage.ensureCurrentChapter()
            currentChapterId = id
            SessionStorage.currentChapterId = id
            text = SessionStorage.getChapterText(id)
            chapterAudioPath = null
            voiceMapping.clear()
            currentBookName = book.title
            SessionStorage.currentBookName = book.title
            statusMessage = "Импортировано: \"${book.title}\" (${book.chapters.size} глав)"
        } catch (e: Exception) {
            statusMessage = "Ошибка импорта $label: ${e.message}"
            e.printStackTrace()
        } finally {
            isLoading = false
            progressMessage = ""
        }
    }

    // ── Voice mapping ─────────────────────────────────────────────────────────

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
        statusMessage = if (unused.isEmpty()) "Нет неиспользуемых голосов" else "Удалено голосов: ${unused.size}"
    }

    fun mergeVoice(fromName: String, toName: String) {
        text = text
            .replace("[$fromName]", "[$toName]")
            .replace("[/$fromName]", "[/$toName]")
        saveCurrentChapter()
        if (viewMode == 1) {
            for (i in segments.indices) {
                if (segments[i].voiceName == fromName) {
                    segments[i] = segments[i].copy(voiceName = toName)
                }
            }
        }
        voiceMapping.remove(fromName)
        if (toName !in voiceMapping) voiceMapping[toName] = VoiceSettings()
        saveVoiceMapping()
        statusMessage = "Голос \"$fromName\" объединён с \"$toName\""
    }

    // ── Segments ──────────────────────────────────────────────────────────────

    fun syncTextFromSegments() {
        text = TextParser.buildText(segments.toList())
    }

    fun resetMarkup() {
        text = if (originalText.isNotBlank()) originalText
               else TextParser.parse(text).joinToString("\n\n") { it.text }
        saveCurrentChapter()
        markupModeEnabled = false
        viewMode = 0
        validationResult = null
        showResetMarkupDialog = false
    }

    fun syncSegmentsFromText() {
        segments.clear()
        segments.addAll(TextParser.parse(text))
        // Auto-save original text if markup exists but original wasn't saved yet
        if (originalText.isBlank() && hasMarkers) {
            originalText = segments.joinToString("\n\n") { it.text }
            SessionStorage.setOriginalText(currentChapterId, originalText)
        }
        revalidate()
    }

    fun wrapTextAsMarkup() {
        if (text.isBlank() || hasMarkers) return
        originalText = text
        SessionStorage.setOriginalText(currentChapterId, originalText)
        text = "[voice_main]\n$text\n[/voice_main]"
        markupModeEnabled = true
        textHadOriginalMarkup = false
        revalidate()
    }

    fun enableMarkupMode() {
        if (!hasMarkers) return
        if (originalText.isBlank()) {
            originalText = text
            SessionStorage.setOriginalText(currentChapterId, originalText)
        }
        markupModeEnabled = true
        textHadOriginalMarkup = true
        revalidate()
    }

    fun unwrapMarkup() {
        // If text originally had markup (enableMarkupMode was used), keep text as-is
        // If markup was added by us (wrapTextAsMarkup), restore original
        if (!textHadOriginalMarkup) {
            text = if (originalText.isNotBlank()) originalText
                   else TextParser.parse(text).joinToString("\n\n") { it.text }
            originalText = ""
            SessionStorage.setOriginalText(currentChapterId, "")
            saveCurrentChapter()
        }
        markupModeEnabled = false
        viewMode = 0
        validationResult = null
    }

    fun updateOriginalText(newOriginal: String) {
        val oldOriginal = originalText
        originalText = newOriginal
        SessionStorage.setOriginalText(currentChapterId, newOriginal)
        // Sync changes to markup: replace text content inside voice tags
        text = syncOriginalToMarkup(oldOriginal, newOriginal, text)
        revalidate()
    }

    /**
     * Propagate edits from original text to markup.
     * Splits both old and new originals by paragraphs, then replaces
     * the corresponding text inside voice tags in the markup.
     */
    private fun syncOriginalToMarkup(oldOriginal: String, newOriginal: String, markup: String): String {
        val oldParagraphs = oldOriginal.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
        val newParagraphs = newOriginal.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
        var result = markup
        // For each changed paragraph, find its old content in markup and replace
        for (i in oldParagraphs.indices) {
            if (i >= newParagraphs.size) break
            if (oldParagraphs[i] != newParagraphs[i]) {
                result = result.replace(oldParagraphs[i], newParagraphs[i])
            }
        }
        // If new paragraphs were added at the end, append them before the last closing tag
        if (newParagraphs.size > oldParagraphs.size) {
            val extra = newParagraphs.drop(oldParagraphs.size).joinToString("\n\n")
            val lastCloseTag = result.lastIndexOf("[/")
            if (lastCloseTag >= 0) {
                result = result.substring(0, lastCloseTag) + extra + "\n" + result.substring(lastCloseTag)
            }
        }
        return result
    }

    // ── Synthesis ─────────────────────────────────────────────────────────────

    fun clearCache() {
        SessionStorage.clearChapterCache(currentChapterId)
        statusMessage = "Кэш очищен"
    }

    fun launchSimpleSynthesis() {
        val settings = voiceMapping["voice_main"] ?: VoiceSettings()
        val outputFormat = if (synthesisBackend == SynthesisBackend.Local) "wav" else selectedFormat
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = "Синтез речи\nголос: ${settings.voice}"
            try {
                SpeechSynthesizer.synthesize(
                    text = text,
                    voiceSettings = settings,
                    outputFormat = outputFormat,
                    backend = synthesisBackend,
                    localSettings = localTtsSettings,
                    cloudToken = TokenStorage.iamToken,
                ).collectLatest { result ->
                    when (result) {
                        is SynthesisResult.InProgress ->
                            progressMessage = "Синтез речи\nголос: ${settings.voice}\n${result.message}"
                        is SynthesisResult.Done -> {
                            val chapterName = chapters.find { it.id == currentChapterId }?.name ?: ""
                            val filePath = saveAudioFile(result.bytes, outputFormat, currentBookName, chapterName)
                            SessionStorage.setChapterAudioPath(currentChapterId, filePath)
                            chapterAudioPath = filePath
                            statusMessage = "Сохранено: $filePath"
                        }
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Ошибка: ${e.message}"
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    fun launchMultiVoiceSynthesis(retryVoice: String? = null) {
        val segmentsList = TextParser.parse(text)
        if (segmentsList.isEmpty()) {
            statusMessage = "Ошибка: не найдены сегменты текста"
            return
        }
        saveVoiceMapping()
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = ""
            val isLocal = synthesisBackend == SynthesisBackend.Local
            val partExt = if (isLocal) "wav" else "mp3"
            val outputFormat = if (isLocal) "wav" else "mp3"
            try {
                val cacheDir = withContext(Dispatchers.IO) {
                    SessionStorage.getChapterCacheDir(currentChapterId)
                }
                val errors = mutableListOf<String>()

                for ((index, segment) in segmentsList.withIndex()) {
                    val settings = if (segment.voiceName != null) {
                        voiceMapping[segment.voiceName] ?: VoiceSettings()
                    } else {
                        VoiceSettings()
                    }
                    val partFile = File(cacheDir, "part_%03d.$partExt".format(index))

                    if (partFile.exists() && (retryVoice == null || segment.voiceName != retryVoice)) {
                        progressMessage = "Озвучивание ${index + 1} из ${segmentsList.size} — кэш"
                        continue
                    }

                    try {
                        SpeechSynthesizer.synthesize(
                            text = segment.text,
                            voiceSettings = settings,
                            outputFormat = outputFormat,
                            backend = synthesisBackend,
                            localSettings = localTtsSettings,
                            cloudToken = TokenStorage.iamToken,
                        ).collectLatest { result ->
                            when (result) {
                                is SynthesisResult.InProgress ->
                                    progressMessage = "Озвучивание ${index + 1} из ${segmentsList.size}\n" +
                                            "голос: ${segment.voiceName ?: "по умолчанию"} → ${settings.voice}\n${result.message}"
                                is SynthesisResult.Done ->
                                    withContext(Dispatchers.IO) { partFile.writeBytes(result.bytes) }
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("#${index + 1} ${segment.voiceName ?: "по умолчанию"}: ${e.message}")
                        partFile.delete()
                    }
                }

                progressMessage = "Склейка аудио\n${segmentsList.size} сегментов"
                val allParts = (0 until segmentsList.size).mapNotNull { i ->
                    val f = File(cacheDir, "part_%03d.$partExt".format(i))
                    if (f.exists()) f.readBytes() else null
                }

                if (allParts.isEmpty()) {
                    statusMessage = "Ошибка: ни один сегмент не озвучен"
                } else {
                    val combined = if (isLocal) {
                        WavMerge.merge(allParts)
                    } else {
                        allParts.reduce { acc, bytes -> acc + bytes }
                    }
                    val chapterName = chapters.find { it.id == currentChapterId }?.name ?: ""
                    val filePath = saveAudioFile(combined, outputFormat, currentBookName, chapterName)
                    SessionStorage.setChapterAudioPath(currentChapterId, filePath)
                    chapterAudioPath = filePath
                    statusMessage = if (errors.isEmpty()) {
                        "Сохранено (${allParts.size} сегментов): $filePath"
                    } else {
                        "Сохранено (${allParts.size}/${segmentsList.size}): $filePath\nОшибки:\n${errors.joinToString("\n")}"
                    }
                }
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    // ── Auto-markup ───────────────────────────────────────────────────────────

    fun launchAutoMarkup() {
        val folderId = TokenStorage.folderId
        if (folderId.isBlank()) {
            showFolderIdDialog = true
            return
        }
        if (text.isBlank() || isLoading) return
        isLoading = true
        progressMessage = "Авто-разметка..."
        statusMessage = ""
        scope.launch {
            try {
                AiMarkupApi.autoMarkup(
                    text = text,
                    token = TokenStorage.iamToken,
                    folderId = folderId,
                    existingVoices = voiceMapping.keys.toSet(),
                ).collectLatest { result ->
                    when (result) {
                        is MarkupResult.InProgress -> progressMessage = result.message
                        is MarkupResult.Done -> {
                            originalText = text
                            SessionStorage.setOriginalText(currentChapterId, text)
                            text = result.text
                            saveCurrentChapter()
                            markupModeEnabled = true
                            revalidate()
                            statusMessage = "Авто-разметка завершена"
                        }
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Ошибка авто-разметки: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    fun remarkupSegment(index: Int) {
        val folderId = TokenStorage.folderId
        if (folderId.isBlank()) {
            showFolderIdDialog = true
            return
        }
        if (isLoading) return
        isLoading = true
        scope.launch {
            try {
                AiMarkupApi.fixDialog(
                    text = segments[index].text,
                    token = TokenStorage.iamToken,
                    folderId = folderId,
                ).collectLatest { result ->
                    when (result) {
                        is MarkupResult.InProgress ->
                            progressMessage = "Исправление диалогов сегмента ${index + 1}\n${result.message}"
                        is MarkupResult.Done -> {
                            val newSegments = TextParser.parse(result.text)
                            if (newSegments.isNotEmpty()) {
                                segments.removeAt(index)
                                segments.addAll(index, newSegments)
                                for (seg in newSegments) {
                                    val name = seg.voiceName ?: continue
                                    if (name !in voiceMapping) voiceMapping[name] = VoiceSettings()
                                }
                                statusMessage = "Сегмент переразмечен: ${newSegments.size} частей"
                            } else {
                                statusMessage = "Авто-разметка не вернула результат"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Ошибка переразметки: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    // ── Player ────────────────────────────────────────────────────────────────

    fun togglePlayerPlayPause() {
        if (playerIsPlaying) {
            chapterPlayer.pause()
            playerIsPlaying = false
        } else {
            chapterPlayer.play()
            playerIsPlaying = true
        }
    }

    fun seekPlayer(posMs: Long) {
        chapterPlayer.seekTo(posMs)
        playerPositionMs = posMs
    }

    fun resetPlayerState() {
        playerIsPlaying = false
        playerPositionMs = 0L
        playerDurationMs = 0L
        playerReady = false
        chapterPlayer.close()
    }
}

// ── Audio file utility ────────────────────────────────────────────────────────

suspend fun saveAudioFile(
    bytes: ByteArray,
    format: String,
    bookName: String = "",
    chapterName: String = "",
): String = withContext(Dispatchers.IO) {
    val ext = if (format == "oggopus") "ogg" else format
    val dir = File(System.getProperty("user.home"), "SpeechHelper")
    dir.mkdirs()
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val safeName = { s: String -> s.replace(Regex("[^\\w\\s\\-()\\[\\]а-яА-ЯёЁ]"), "_").trim() }
    val nameParts = listOfNotNull(
        bookName.takeIf { it.isNotBlank() }?.let { safeName(it) },
        chapterName.takeIf { it.isNotBlank() }?.let { safeName(it) },
        timestamp,
    )
    val file = File(dir, "${nameParts.joinToString(" - ")}.$ext")
    file.writeBytes(bytes)
    file.absolutePath
}
