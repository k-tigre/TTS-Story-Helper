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
import by.tigre.speechhelper.data.OpenAiMarkupApi
import by.tigre.speechhelper.data.ImportApplyResult
import by.tigre.speechhelper.data.LocalTtsApi
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.data.SpeechSynthesizer
import by.tigre.speechhelper.data.preparedForStorage
import by.tigre.speechhelper.data.SynthesisResult
import by.tigre.speechhelper.domain.LlmConfig
import by.tigre.speechhelper.data.WavMerge
import by.tigre.speechhelper.domain.API_VOICES
import by.tigre.speechhelper.domain.FORMATS
import by.tigre.speechhelper.domain.LocalTtsSettings
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.TextSegment
import by.tigre.speechhelper.domain.ChapterInfo
import by.tigre.speechhelper.domain.ValidationResult
import by.tigre.speechhelper.domain.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private sealed interface ImportBackgroundResult {
    data object EmptyChapters : ImportBackgroundResult
    data class Success(val book: ParsedBook, val apply: ImportApplyResult) : ImportBackgroundResult
}

/** Фильтр списка сегментов в режиме «Разбивка». */
sealed class SegmentViewVoiceFilter {
    data object All : SegmentViewVoiceFilter()
    data class Only(val voiceName: String) : SegmentViewVoiceFilter()
    data object Unvoiced : SegmentViewVoiceFilter()
}

class MainViewModel(private val scope: CoroutineScope) {

    // Chapter management (гидратация с потока SQLite в [init])
    var chapters by mutableStateOf<List<ChapterInfo>>(emptyList())
        private set
    var currentChapterId by mutableStateOf("")
        private set
    var currentBookName by mutableStateOf("")
        private set

    // Chapter text
    var text by mutableStateOf("")
    var originalText by mutableStateOf("")

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
    var progressCancellable by mutableStateOf(false)
        private set

    private var markupProgressJob: Job? = null

    fun cancelMarkupProgress() {
        markupProgressJob?.cancel()
    }
    var statusMessage by mutableStateOf("")

    // Audio path
    var chapterAudioPath by mutableStateOf<String?>(null)
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
    /** UI редактора всегда в виде «оригинал + разметка / разбивка»; флаг оставлен для совместимости вызовов. */
    var markupModeEnabled by mutableStateOf(true)
    private var textHadOriginalMarkup = false
    val segments = mutableStateListOf<TextSegment>()

    var segmentViewVoiceFilter by mutableStateOf<SegmentViewVoiceFilter>(SegmentViewVoiceFilter.All)

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
    var showChaptersWorkflowDialog by mutableStateOf(false)
    var showAudiobookExportBlockedDialog by mutableStateOf(false)
    var audiobookExportBlockedRows by mutableStateOf<List<Pair<String, List<String>>>>(emptyList())
        private set

    var showAudiobookExportDialog by mutableStateOf(false)
    /** Сбрасывает выбор в UI при каждом открытии диалога экспорта */
    var audiobookExportDialogKey by mutableStateOf(0)
        private set
    var audiobookExportValidationError by mutableStateOf("")

    private var pendingMarkupChapterIds: List<String>? = null

    val hasMarkers: Boolean get() = TextParser.hasVoiceMarkers(text)
    val detectedVoices: Set<String> get() = if (hasMarkers) TextParser.extractVoiceNames(text) else emptySet()

    // Paragraph validation
    var validationResult by mutableStateOf<ValidationResult?>(null)
        private set

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

    /**
     * When the editor’s original matches persisted storage, reuse paragraph rows from SQLite
     * (no full-string re-split). Otherwise split the in-memory string (e.g. mid-edit before save).
     */
    private fun originalParagraphsForValidation(): List<String> {
        val persisted = SessionStorage.getOriginalText(currentChapterId)
        if (persisted != originalText) {
            return TextParser.splitParagraphsForStorage(originalText)
        }
        val fromDb = SessionStorage.listChapterParagraphs(currentChapterId)
            .sortedBy { it.ordinal }
            .map { it.originalText }
        if (fromDb.isEmpty() && originalText.isNotBlank()) {
            return TextParser.splitParagraphsForStorage(originalText)
        }
        return fromDb
    }

    init {
        scope.launch {
            val snap = SessionStorage.loadInitialSnapshot()
            chapters = snap.chapters
            currentChapterId = snap.currentChapterId
            currentBookName = snap.currentBookTitle
            text = snap.chapterText
            originalText = snap.originalText
            chapterAudioPath = snap.chapterAudioPath
            voiceMapping.clear()
            voiceMapping.putAll(snap.voiceMapping)
            synthesisBackend = snap.synthesisBackend
            localTtsSettings = snap.localTtsSettings
            ensureVoiceMain()
            revalidate()
        }
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
        val snap = SessionStorage.persistSwitchChapter(currentChapterId, text, id)
        chapterPlayer.close()
        playerIsPlaying = false
        currentChapterId = id
        text = snap.markedJoined
        originalText = snap.originalJoined
        chapterAudioPath = snap.audioPath
        statusMessage = ""
        markupModeEnabled = true
        ensureVoiceMain()
        segmentViewVoiceFilter = SegmentViewVoiceFilter.All
        revalidate(preloadedOriginalParagraphs = snap.originalParagraphs)
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
        segmentViewVoiceFilter = SegmentViewVoiceFilter.All
        markupModeEnabled = true
        ensureVoiceMain()
        revalidate()
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
        currentBookName = SessionStorage.currentBookTitle()
        synthesisBackend = SessionStorage.synthesisBackend
        localTtsSettings = SessionStorage.localTtsSettings
        segmentViewVoiceFilter = SegmentViewVoiceFilter.All
        markupModeEnabled = true
        ensureVoiceMain()
        revalidate()
        statusMessage = "Всё очищено"
        showClearAllDialog = false
    }

    // ── Book management ───────────────────────────────────────────────────────

    fun saveCurrentBook() {
        saveCurrentChapter()
        saveVoiceMapping()
        if (currentBookName.isNotBlank()) {
            SessionStorage.saveBookTitle(currentBookName)
            statusMessage = "Сохранено: \"${SessionStorage.currentBookTitle()}\""
        } else {
            showSaveBookDialog = true
        }
    }

    fun saveBook(bookName: String) {
        saveCurrentChapter()
        saveVoiceMapping()
        SessionStorage.saveBookTitle(bookName)
        currentBookName = SessionStorage.currentBookTitle()
        statusMessage = "Название: \"${currentBookName}\""
        showSaveBookDialog = false
    }

    fun loadBook(bookId: String) {
        saveCurrentChapter()
        if (SessionStorage.loadBook(bookId)) {
            chapters = SessionStorage.listChapters()
            val id = SessionStorage.ensureCurrentChapter()
            currentChapterId = id
            text = SessionStorage.getChapterText(id)
            originalText = SessionStorage.getOriginalText(id)
            chapterAudioPath = SessionStorage.getChapterAudioPath(id)
            voiceMapping.clear()
            voiceMapping.putAll(SessionStorage.voiceMapping)
            currentBookName = SessionStorage.currentBookTitle()
            segmentViewVoiceFilter = SegmentViewVoiceFilter.All
            markupModeEnabled = true
            ensureVoiceMain()
            revalidate()
            statusMessage = "Открыта \"" + currentBookName + "\""
        } else {
            statusMessage = "Ошибка: не удалось открыть книгу"
        }
        showLoadBookDialog = false
    }

    fun importFb2() {
        scope.launch {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выбрать FB2 файл"
                fileFilter = FileNameExtensionFilter("FictionBook 2 (*.fb2)", "fb2")
            }
            val file =
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            if (file == null) return@launch
            importParsedBook(label = "FB2") { Fb2Parser.parse(file) }
        }
    }

    fun importEpub() {
        scope.launch {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выбрать EPUB файл"
                fileFilter = FileNameExtensionFilter("EPUB (*.epub)", "epub")
            }
            val file =
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            if (file == null) return@launch
            importParsedBook(label = "EPUB") { EpubParser.parse(file) }
        }
    }

    private fun postImportProgress(message: String) {
        SwingUtilities.invokeLater {
            progressMessage = message
        }
    }

    private suspend fun importParsedBook(label: String, parse: suspend () -> ParsedBook) {
        val chapterIdSnapshot = currentChapterId
        val textSnapshot = text
        isLoading = true
        progressMessage = "Чтение $label..."
        try {
            val work = withContext(Dispatchers.IO) {
                val book = parse()
                if (book.chapters.isEmpty()) {
                    return@withContext ImportBackgroundResult.EmptyChapters
                }
                withContext(SessionStorage.databaseDispatcher) {
                    SessionStorage.setChapterText(chapterIdSnapshot, textSnapshot)
                }
                postImportProgress("Подготовка текста…")
                val prepared = withContext(Dispatchers.Default) {
                    book.preparedForStorage()
                }
                postImportProgress("Запись в базу…")
                val result = withContext(SessionStorage.databaseDispatcher) {
                    SessionStorage.applyImportedBookPrepared(book.title, prepared)
                }
                ImportBackgroundResult.Success(book, result)
            }

            when (work) {
                ImportBackgroundResult.EmptyChapters -> {
                    statusMessage = "Ошибка: не найдены главы в файле"
                    return
                }
                is ImportBackgroundResult.Success -> {
                    val book = work.book
                    val result = work.apply
                    chapterPlayer.close()
                    playerIsPlaying = false
                    chapters = result.chapters
                    currentChapterId = result.firstChapterId
                    val snap = result.initialEditor
                    text = snap.markedJoined
                    originalText = snap.originalJoined
                    chapterAudioPath = snap.audioPath
                    voiceMapping.clear()
                    currentBookName = result.bookTitle
                    segmentViewVoiceFilter = SegmentViewVoiceFilter.All
                    markupModeEnabled = true
                    ensureVoiceMain()
                    revalidate(preloadedOriginalParagraphs = snap.originalParagraphs)
                    statusMessage =
                        "Добавлено в библиотеку: \"${result.bookTitle}\" (${book.chapters.size} гл.). Открыть другую — «Загрузить книгу»."
                }
            }
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

    fun refreshChapters() {
        chapters = SessionStorage.listChapters()
    }

    fun audioFileExistsForChapter(id: String): Boolean {
        val p = SessionStorage.getChapterAudioPath(id) ?: return false
        return File(p).isFile
    }

    private fun chapterAudiobookExportIssues(ch: ChapterInfo): List<String> {
        val issues = mutableListOf<String>()
        if (!ch.markupDone) issues.add("не отмечена «Разметка готова»")
        val hasAudio = audioFileExistsForChapter(ch.id)
        if (!hasAudio) issues.add("нет файла озвучки")
        if (hasAudio && !ch.voiceDone) issues.add("не отмечена «Озвучка готова»")
        return issues
    }

    fun chapterAudiobookExportEligibilityIssues(ch: ChapterInfo): List<String> =
        chapterAudiobookExportIssues(ch)

    fun isAudiobookExportReady(): Boolean =
        chapters.isNotEmpty() && chapters.all { chapterAudiobookExportIssues(it).isEmpty() }

    fun dismissAudiobookExportBlockedDialog() {
        showAudiobookExportBlockedDialog = false
        audiobookExportBlockedRows = emptyList()
    }

    fun dismissAudiobookExportDialog() {
        showAudiobookExportDialog = false
        audiobookExportValidationError = ""
    }

    fun defaultAudiobookExportSelection(): Set<String> {
        val list = SessionStorage.listChapters()
        return list
            .filter { chapterAudiobookExportIssues(it).isEmpty() && !it.exported }
            .map { it.id }
            .toSet()
    }

    /**
     * Между любыми двумя выбранными главами по порядку книги все промежуточные главы
     * должны быть уже отмечены как экспортированные (чтобы «дырки» в нумерации были осознанными).
     */
    fun validateAudiobookExportSelection(selectedIds: List<String>): String? {
        chapters = SessionStorage.listChapters()
        val ids = selectedIds.distinct()
        if (ids.isEmpty()) return "Отметьте хотя бы одну главу"
        val order = chapters.map { it.id }
        for (id in ids) {
            val ch = chapters.find { it.id == id } ?: return "Неизвестная глава"
            val issues = chapterAudiobookExportIssues(ch)
            if (issues.isNotEmpty()) {
                return "«${ch.name}»: ${issues.joinToString("; ")}"
            }
        }
        val selectedSorted = ids.sortedBy { order.indexOf(it) }
        for (i in 0 until selectedSorted.size - 1) {
            val a = order.indexOf(selectedSorted[i])
            val b = order.indexOf(selectedSorted[i + 1])
            for (k in a + 1 until b) {
                val mid = chapters[k]
                if (!mid.exported) {
                    return "Между «${chapters[a].name}» и «${chapters[b].name}» глава «${mid.name}» " +
                        "ещё не в экспорте. Добавьте её в выбор или сначала экспортируйте промежуточные главы."
                }
            }
        }
        return null
    }

    fun submitAudiobookExport(selectedIds: List<String>) {
        chapters = SessionStorage.listChapters()
        audiobookExportValidationError = ""
        val err = validateAudiobookExportSelection(selectedIds)
        if (err != null) {
            audiobookExportValidationError = err
            return
        }
        showAudiobookExportDialog = false
        scope.launch { runAudiobookExportForChapterIds(selectedIds) }
    }

    fun requestAudiobookExport() {
        chapters = SessionStorage.listChapters()
        if (chapters.isEmpty()) {
            audiobookExportBlockedRows = listOf("Книга" to listOf("нет ни одной главы"))
            showAudiobookExportBlockedDialog = true
            return
        }
        audiobookExportValidationError = ""
        audiobookExportDialogKey++
        showAudiobookExportDialog = true
    }

    private suspend fun runAudiobookExportForChapterIds(chapterIds: List<String>) {
        chapters = SessionStorage.listChapters()
        val order = chapters.map { it.id }
        val toExport = chapterIds.distinct().sortedBy { order.indexOf(it) }
        val parentDir = withContext(Dispatchers.IO) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите папку для экспорта аудиокниги"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
        if (parentDir == null) return

        val bookLabel = currentBookName.ifBlank { "Аудиокнига" }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val folderName = sanitizeAudioFileNamePart(bookLabel).ifBlank { "Аудиокнига" }
                val destRoot = File(parentDir, folderName)
                destRoot.mkdirs()
                if (!destRoot.isDirectory) {
                    error("Не удалось создать папку: ${destRoot.absolutePath}")
                }
                val numWidth = maxOf(2, chapters.size.toString().length)
                for (chId in toExport) {
                    val ch = chapters.find { it.id == chId }
                        ?: error("Нет главы: $chId")
                    val srcPath = SessionStorage.getChapterAudioPath(ch.id)
                        ?: error("Нет пути к аудио: ${ch.name}")
                    val src = File(srcPath)
                    if (!src.isFile) error("Нет файла озвучки: ${ch.name}")
                    val ext = src.extension.ifBlank { "mp3" }
                    val index1 = order.indexOf(chId) + 1
                    val num = index1.toString().padStart(numWidth, '0')
                    val partName = sanitizeAudioFileNamePart(ch.name).ifBlank { "глава_$index1" }
                    val dest = File(destRoot, "$num - $partName.$ext")
                    src.copyTo(dest, overwrite = true)
                }
                for (chId in toExport) {
                    SessionStorage.setChapterExported(chId, true)
                }
                destRoot.absolutePath
            }
        }
        refreshChapters()
        statusMessage = result.fold(
            onSuccess = { "Экспортировано глав: ${toExport.size} → $it" },
            onFailure = { "Ошибка экспорта: ${it.message ?: it.javaClass.simpleName}" },
        )
    }

    fun setChapterMarkupDoneFlag(id: String, done: Boolean) {
        SessionStorage.setChapterMarkupDone(id, done)
        refreshChapters()
    }

    fun setChapterVoiceDoneFlag(id: String, done: Boolean) {
        SessionStorage.setChapterVoiceDone(id, done)
        refreshChapters()
    }

    fun toggleCurrentChapterMarkupDone() {
        val ch = chapters.find { it.id == currentChapterId } ?: return
        setChapterMarkupDoneFlag(currentChapterId, !ch.markupDone)
    }

    fun toggleCurrentChapterVoiceDone() {
        if (!audioFileExistsForChapter(currentChapterId)) return
        val ch = chapters.find { it.id == currentChapterId } ?: return
        setChapterVoiceDoneFlag(currentChapterId, !ch.voiceDone)
    }

    fun dismissFolderIdDialog() {
        showFolderIdDialog = false
        pendingMarkupChapterIds = null
    }

    fun onFolderIdSaved(folderId: String) {
        TokenStorage.folderId = folderId
        showFolderIdDialog = false
        val ids = pendingMarkupChapterIds ?: listOf(currentChapterId)
        pendingMarkupChapterIds = null
        executeAutoMarkupForChapters(ids)
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
        fun applyVoiceRename(raw: String): String =
            raw.replace("[$fromName]", "[$toName]").replace("[/$fromName]", "[/$toName]")

        text = applyVoiceRename(text)
        saveCurrentChapter()
        for (ch in SessionStorage.listChapters()) {
            if (ch.id == currentChapterId) continue
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
        statusMessage = "Голос \"$fromName\" объединён с \"$toName\" во всех главах"
    }

    // ── Segments ──────────────────────────────────────────────────────────────

    fun syncTextFromSegments() {
        text = TextParser.buildText(segments.toList())
    }

    fun mergeSegmentWithPrevious(index: Int) {
        if (index <= 0 || index >= segments.size) return
        val prev = segments[index - 1]
        val curr = segments[index]
        segments[index - 1] = prev.copy(text = joinMergedSegmentTexts(prev.text, curr.text))
        segments.removeAt(index)
        syncTextFromSegments()
        saveCurrentChapter()
        revalidate()
        statusMessage = "Сегмент объединён с предыдущим"
    }

    fun mergeSegmentWithNext(index: Int) {
        if (index < 0 || index >= segments.size - 1) return
        val curr = segments[index]
        val next = segments[index + 1]
        segments[index] = curr.copy(text = joinMergedSegmentTexts(curr.text, next.text))
        segments.removeAt(index + 1)
        syncTextFromSegments()
        saveCurrentChapter()
        revalidate()
        statusMessage = "Сегмент объединён со следующим"
    }

    fun resetMarkup() {
        text = if (originalText.isNotBlank()) originalText
               else TextParser.parse(text).joinToString("\n\n") { it.text }
        saveCurrentChapter()
        markupModeEnabled = true
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
        markupModeEnabled = true
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
        val oldParagraphs = TextParser.splitParagraphsForStorage(oldOriginal)
        val newParagraphs = TextParser.splitParagraphsForStorage(newOriginal)
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
        val outputFormat = selectedFormat
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

    private data class MultiVoiceSynthResult(
        val savedPath: String?,
        val segmentCount: Int,
        val partsMerged: Int,
        val segmentErrors: List<String>,
    )

    /**
     * Синтез многоголосой главы по тексту из хранилища (кэш частей, склейка, сохранение файла).
     * [progressContext] — префикс для [progressMessage] (например, номер главы в пакете).
     */
    private suspend fun synthesizeMultiVoiceForStoredChapter(
        chapterId: String,
        chapterText: String,
        retryVoice: String?,
        progressContext: String,
    ): MultiVoiceSynthResult {
        fun pushProgress(inner: String) {
            progressMessage =
                if (progressContext.isNotBlank()) "$progressContext\n$inner" else inner
        }

        if (!TextParser.hasVoiceMarkers(chapterText)) {
            val trimmed = chapterText.trim()
            if (trimmed.isEmpty()) {
                return MultiVoiceSynthResult(null, 0, 0, listOf("пустой текст"))
            }
            val settings = voiceMapping["voice_main"] ?: VoiceSettings()
            val outputFormat = if (synthesisBackend == SynthesisBackend.Local) selectedFormat else "mp3"
            var savedPath: String? = null
            val errors = mutableListOf<String>()
            try {
                SpeechSynthesizer.synthesize(
                    text = chapterText,
                    voiceSettings = settings,
                    outputFormat = outputFormat,
                    backend = synthesisBackend,
                    localSettings = localTtsSettings,
                    cloudToken = TokenStorage.iamToken,
                ).collectLatest { result ->
                    when (result) {
                        is SynthesisResult.InProgress ->
                            pushProgress(
                                "Синтез речи\nголос: ${settings.voice}\n${result.message}",
                            )
                        is SynthesisResult.Done -> {
                            val chapterName = chapters.find { it.id == chapterId }?.name ?: ""
                            val path = saveAudioFile(result.bytes, outputFormat, currentBookName, chapterName)
                            SessionStorage.setChapterAudioPath(chapterId, path)
                            if (chapterId == currentChapterId) {
                                chapterAudioPath = path
                            }
                            savedPath = path
                        }
                    }
                }
            } catch (e: Exception) {
                errors.add(e.message ?: "ошибка синтеза")
            }
            if (savedPath == null && errors.isEmpty()) {
                errors.add("нет результата синтеза")
            }
            return MultiVoiceSynthResult(
                savedPath = savedPath,
                segmentCount = 1,
                partsMerged = if (savedPath != null) 1 else 0,
                segmentErrors = errors,
            )
        }

        val segmentsList = TextParser.parse(chapterText)
        if (segmentsList.isEmpty()) {
            return MultiVoiceSynthResult(null, 0, 0, listOf("не найдены сегменты текста"))
        }
        val isLocal = synthesisBackend == SynthesisBackend.Local
        val outputFormat = if (isLocal) selectedFormat else "mp3"
        val partExt = outputFormat
        val cacheDir = withContext(Dispatchers.IO) {
            SessionStorage.getChapterCacheDir(chapterId)
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
                pushProgress("Озвучивание ${index + 1} из ${segmentsList.size} — кэш")
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
                            pushProgress(
                                "Озвучивание ${index + 1} из ${segmentsList.size}\n" +
                                    "голос: ${segment.voiceName ?: "по умолчанию"} → ${settings.voice}\n${result.message}",
                            )
                        is SynthesisResult.Done ->
                            withContext(Dispatchers.IO) { partFile.writeBytes(result.bytes) }
                    }
                }
            } catch (e: Exception) {
                errors.add("#${index + 1} ${segment.voiceName ?: "по умолчанию"}: ${e.message}")
                partFile.delete()
            }
        }

        pushProgress("Склейка аудио\n${segmentsList.size} сегментов")
        val allParts = (0 until segmentsList.size).mapNotNull { i ->
            val f = File(cacheDir, "part_%03d.$partExt".format(i))
            if (f.exists()) f.readBytes() else null
        }

        if (allParts.isEmpty()) {
            return MultiVoiceSynthResult(null, segmentsList.size, 0, errors)
        }
        val combined =
            if (outputFormat == "wav") {
                WavMerge.merge(allParts)
            } else {
                allParts.reduce { acc, bytes -> acc + bytes }
            }
        val chapterName = chapters.find { it.id == chapterId }?.name ?: ""
        val filePath = saveAudioFile(combined, outputFormat, currentBookName, chapterName)
        SessionStorage.setChapterAudioPath(chapterId, filePath)
        if (chapterId == currentChapterId) {
            chapterAudioPath = filePath
        }
        return MultiVoiceSynthResult(filePath, segmentsList.size, allParts.size, errors)
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
            try {
                val r = synthesizeMultiVoiceForStoredChapter(
                    chapterId = currentChapterId,
                    chapterText = text,
                    retryVoice = retryVoice,
                    progressContext = "",
                )
                if (r.savedPath == null) {
                    statusMessage = when {
                        r.segmentCount == 0 -> "Ошибка: не найдены сегменты текста"
                        r.partsMerged == 0 -> "Ошибка: ни один сегмент не озвучен"
                        else -> "Ошибка озвучки"
                    }
                } else {
                    statusMessage = if (r.segmentErrors.isEmpty()) {
                        "Сохранено (${r.partsMerged} сегментов): ${r.savedPath}"
                    } else {
                        "Сохранено (${r.partsMerged}/${r.segmentCount}): ${r.savedPath}\nОшибки:\n" +
                            r.segmentErrors.joinToString("\n")
                    }
                }
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    fun launchBatchSynthesisForChapters(chapterIds: List<String>) {
        val distinct = chapterIds.distinct().filter { SessionStorage.getChapterText(it).isNotBlank() }
        if (distinct.isEmpty() || isLoading) return
        saveVoiceMapping()
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = ""
            try {
                saveCurrentChapter()
                refreshChapters()
                val lines = mutableListOf<String>()
                var ok = 0
                for ((idx, id) in distinct.withIndex()) {
                    val label = chapters.find { it.id == id }?.name ?: id
                    val t = SessionStorage.getChapterText(id)
                    val ctx = "Пакетная озвучка: глава ${idx + 1} из ${distinct.size} — $label"
                    val r = synthesizeMultiVoiceForStoredChapter(
                        chapterId = id,
                        chapterText = t,
                        retryVoice = null,
                        progressContext = ctx,
                    )
                    when {
                        r.savedPath == null ->
                            lines.add(
                                "$label: " + r.segmentErrors.firstOrNull()
                                    .orEmpty().ifBlank { "не удалось сохранить аудио" },
                            )
                        r.segmentErrors.isNotEmpty() -> {
                            ok++
                            lines.add("$label: сохранено с ошибками сегментов\n${r.segmentErrors.joinToString("\n")}")
                        }
                        else -> {
                            ok++
                            SessionStorage.setChapterVoiceDone(id, true)
                        }
                    }
                }
                refreshChapters()
                statusMessage = buildString {
                    append("Пакетная озвучка: готово $ok из ${distinct.size}")
                    if (lines.isNotEmpty()) {
                        append("\n")
                        append(lines.joinToString("\n"))
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Пакетная озвучка: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    // ── Auto-markup ───────────────────────────────────────────────────────────

    fun launchAutoMarkup() {
        launchAutoMarkupForChapters(listOf(currentChapterId))
    }

    fun launchAutoMarkupForChapters(chapterIds: List<String>) {
        val distinct = chapterIds.distinct().filter { id ->
            SessionStorage.getChapterText(id).isNotBlank()
        }
        if (distinct.isEmpty() || isLoading) return
        val llmConfig = TokenStorage.llmConfig
        if (llmConfig.isConfigured) {
            launchAutoMarkupWithLlm(llmConfig, distinct)
            return
        }

        if (TokenStorage.folderId.isBlank()) {
            pendingMarkupChapterIds = distinct
            showFolderIdDialog = true
            return
        }
        executeAutoMarkupForChapters(distinct)
    }

    private fun executeAutoMarkupForChapters(chapterIds: List<String>) {
        if (chapterIds.isEmpty() || isLoading) return
        markupProgressJob?.cancel()
        isLoading = true
        progressCancellable = true
        progressMessage = "Авто-разметка..."
        statusMessage = ""
        markupProgressJob = scope.launch {
            try {
                saveCurrentChapter()
                val errors = mutableListOf<String>()
                val folderId = TokenStorage.folderId
                for ((index, id) in chapterIds.withIndex()) {
                    val raw = SessionStorage.getChapterText(id)
                    if (raw.isBlank()) continue
                    try {
                        AiMarkupApi.autoMarkup(
                            text = raw,
                            token = TokenStorage.iamToken,
                            folderId = folderId,
                            existingVoices = voiceMapping.keys.toSet(),
                        ).collectLatest { result ->
                            when (result) {
                                is MarkupResult.InProgress ->
                                    progressMessage =
                                        "Авто-разметка ${index + 1} из ${chapterIds.size}\n${result.message}"
                                is MarkupResult.Done -> {
                                    SessionStorage.setOriginalText(id, raw)
                                    SessionStorage.setChapterText(id, result.text)
                                    if (id == currentChapterId) {
                                        originalText = raw
                                        text = result.text
                                        markupModeEnabled = true
                                        revalidate()
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val label = chapters.find { it.id == id }?.name ?: id
                        errors.add("$label: ${e.message}")
                        e.printStackTrace()
                    }
                }
                refreshChapters()
                statusMessage = when {
                    errors.isEmpty() ->
                        "Авто-разметка: готово (${chapterIds.size} ${chapterWord(chapterIds.size)})"
                    else ->
                        "Авто-разметка: есть ошибки (${errors.size})\n${errors.joinToString("\n")}"
                }
            } catch (e: CancellationException) {
                statusMessage = "Авто-разметка отменена"
                throw e
            } catch (e: Exception) {
                statusMessage = "Ошибка авто-разметки: ${e.message}"
                e.printStackTrace()
            } finally {
                progressCancellable = false
                isLoading = false
                progressMessage = ""
                markupProgressJob = null
            }
        }
    }

    private fun chapterWord(n: Int): String =
        when {
            n % 10 == 1 && n % 100 != 11 -> "глава"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "главы"
            else -> "глав"
        }

    private fun launchAutoMarkupWithLlm(config: LlmConfig, chapterIds: List<String>) {
        if (chapterIds.isEmpty() || isLoading) return
        markupProgressJob?.cancel()
        isLoading = true
        progressCancellable = true
        progressMessage = "Авто-разметка (${config.model})..."
        statusMessage = ""
        markupProgressJob = scope.launch {
            try {
                saveCurrentChapter()
                val errors = mutableListOf<String>()
                val existingVoices = voiceMapping.keys.toSet()
                for ((index, id) in chapterIds.withIndex()) {
                    val raw = SessionStorage.getChapterText(id)
                    if (raw.isBlank()) continue
                    try {
                        OpenAiMarkupApi.autoMarkup(
                            text = raw,
                            config = config,
                            existingVoices = existingVoices,
                        ).collectLatest { result ->
                            when (result) {
                                is MarkupResult.InProgress ->
                                    progressMessage =
                                        "Авто-разметка ${index + 1} из ${chapterIds.size}\n${result.message}"
                                is MarkupResult.Done -> {
                                    SessionStorage.setOriginalText(id, raw)
                                    SessionStorage.setChapterText(id, result.text)
                                    if (id == currentChapterId) {
                                        originalText = raw
                                        text = result.text
                                        markupModeEnabled = true
                                        revalidate()
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val label = chapters.find { it.id == id }?.name ?: id
                        errors.add("$label: ${e.message}")
                        e.printStackTrace()
                    }
                }
                refreshChapters()
                statusMessage = when {
                    errors.isEmpty() ->
                        "Авто-разметка: готово (${chapterIds.size} ${chapterWord(chapterIds.size)}) (${config.model})"
                    else ->
                        "Авто-разметка: есть ошибки (${errors.size})\n${errors.joinToString("\n")}"
                }
            } catch (e: CancellationException) {
                statusMessage = "Авто-разметка отменена"
                throw e
            } catch (e: Exception) {
                statusMessage = "Ошибка авто-разметки: ${e.message}"
                e.printStackTrace()
            } finally {
                progressCancellable = false
                isLoading = false
                progressMessage = ""
                markupProgressJob = null
            }
        }
    }

    fun remarkupSegment(index: Int) {
        if (isLoading) return
        val llmConfig = TokenStorage.llmConfig
        val markupFlow = if (llmConfig.isConfigured) {
            OpenAiMarkupApi.fixDialog(text = segments[index].text, config = llmConfig)
        } else {
            val folderId = TokenStorage.folderId
            if (folderId.isBlank()) {
                showFolderIdDialog = true
                return
            }
            AiMarkupApi.fixDialog(text = segments[index].text, token = TokenStorage.iamToken, folderId = folderId)
        }
        markupProgressJob?.cancel()
        isLoading = true
        progressCancellable = true
        progressMessage = "Исправление диалогов сегмента ${index + 1}..."
        markupProgressJob = scope.launch {
            try {
                markupFlow.collectLatest { result ->
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
            } catch (e: CancellationException) {
                statusMessage = "Переразметка отменена"
                throw e
            } catch (e: Exception) {
                statusMessage = "Ошибка переразметки: ${e.message}"
                e.printStackTrace()
            } finally {
                progressCancellable = false
                isLoading = false
                progressMessage = ""
                markupProgressJob = null
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

private fun joinMergedSegmentTexts(a: String, b: String): String {
    val x = a.trim()
    val y = b.trim()
    return when {
        x.isEmpty() -> y
        y.isEmpty() -> x
        else -> "$x\n\n$y"
    }
}

private fun sanitizeAudioFileNamePart(s: String): String =
    s.replace(Regex("[^\\w\\s\\-()\\[\\]а-яА-ЯёЁ]"), "_").trim()

suspend fun saveAudioFile(
    bytes: ByteArray,
    format: String,
    bookName: String = "",
    chapterName: String = "",
): String = withContext(Dispatchers.IO) {
    val ext = if (format == "oggopus") "ogg" else format
    val dir = File(System.getProperty("user.home"), "SpeechHelper")
    dir.mkdirs()
    val nameParts = listOfNotNull(
        bookName.takeIf { it.isNotBlank() }?.let { sanitizeAudioFileNamePart(it) },
        chapterName.takeIf { it.isNotBlank() }?.let { sanitizeAudioFileNamePart(it) },
    )
    val base = if (nameParts.isEmpty()) "audio" else nameParts.joinToString(" - ")
    val file = File(dir, "$base.$ext")
    file.writeBytes(bytes)
    file.absolutePath
}
