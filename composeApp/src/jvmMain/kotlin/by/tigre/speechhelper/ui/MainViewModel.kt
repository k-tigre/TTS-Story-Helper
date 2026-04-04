package by.tigre.speechhelper.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.AiMarkupApi
import by.tigre.speechhelper.data.AutoMarkupBatchAlign
import by.tigre.speechhelper.data.AutoMarkupFingerprint
import by.tigre.speechhelper.data.AutoMarkupOrderCheck
import by.tigre.speechhelper.data.AutoMarkupParagraphPlanner
import by.tigre.speechhelper.data.MarkupResult
import by.tigre.speechhelper.data.MarkupSystemPrompts
import by.tigre.speechhelper.data.OpenAiMarkupApi
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.data.SpeechSynthesizer
import by.tigre.speechhelper.data.SynthesisResult
import by.tigre.speechhelper.data.WavMerge
import by.tigre.speechhelper.domain.AutoMarkupMode
import by.tigre.speechhelper.domain.ChapterInfo
import by.tigre.speechhelper.domain.LlmConfig
import by.tigre.speechhelper.domain.LocalTtsSettings
import by.tigre.speechhelper.data.LocalTtsApi
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.ValidationResult
import by.tigre.speechhelper.domain.VoiceSettings
import by.tigre.speechhelper.ui.vm.AppDialogState
import by.tigre.speechhelper.ui.vm.BookLibraryViewModel
import by.tigre.speechhelper.ui.vm.EditorWorkspaceViewModel
import by.tigre.speechhelper.ui.vm.PlayerViewModel
import by.tigre.speechhelper.ui.vm.SegmentViewVoiceFilter
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Координатор: синтез, авторазметка, экспорт аудиокниги, прогресс.
 * Состояние книги/глав — [library], редактора — [editor], плеера — [player].
 */
class MainViewModel(
    private val scope: CoroutineScope,
    val dialogs: AppDialogState,
    val player: PlayerViewModel,
) {

    val library: BookLibraryViewModel
    val editor: EditorWorkspaceViewModel

    private var pendingAutoMarkupWork: Pair<List<String>, AutoMarkupMode>? = null

    /** Индексы абзацев (как в [TextParser.splitParagraphsForStorage]), где пакетная модель не сопоставилась с ответом. */
    private var remarkupNeededParagraphs by mutableStateOf<Map<String, Set<Int>>>(emptyMap())

    /** Для текущей главы: абзацы, которые можно переразметить вручную после сбоя сопоставления батча. */
    val remarkupNeededParagraphIndices: Set<Int>
        get() = remarkupNeededParagraphs[library.currentChapterId].orEmpty()

    var synthesisBackend by mutableStateOf(SessionStorage.synthesisBackend)
    var localTtsSettings by mutableStateOf(SessionStorage.localTtsSettings)

    var isLoading by mutableStateOf(false)
        private set
    var progressMessage by mutableStateOf("")
        private set
    var progressCancellable by mutableStateOf(false)
        private set

    private var markupProgressJob: Job? = null

    var statusMessage by mutableStateOf("")

    init {
        lateinit var libraryRef: BookLibraryViewModel
        editor = EditorWorkspaceViewModel { libraryRef.currentChapterId }
        libraryRef = BookLibraryViewModel(
            scope = scope,
            dialogs = dialogs,
            player = player,
            editor = editor,
            status = { statusMessage = it },
            setImportLoading = { isLoading = it },
            setProgressMessage = { msg ->
                javax.swing.SwingUtilities.invokeLater { progressMessage = msg }
            },
        )
        library = libraryRef
        scope.launch {
            val snap = SessionStorage.loadInitialSnapshot()
            library.hydrateFromInitialSnapshot(snap)
            editor.hydrateFromInitialSnapshot(snap)
            synthesisBackend = snap.synthesisBackend
            localTtsSettings = snap.localTtsSettings
        }
    }

    // ── UI bridges (совместимость с экранами) ──────────────────────────────────

    val chapters: List<ChapterInfo> get() = library.chapters
    val currentChapterId: String get() = library.currentChapterId
    val currentBookName: String get() = library.currentBookName
    val chapterAudioPath: String? get() = library.chapterAudioPath

    var text: String
        get() = editor.text
        set(value) {
            editor.text = value
        }
    var originalText: String
        get() = editor.originalText
        set(value) {
            editor.originalText = value
        }
    var viewMode: Int
        get() = editor.viewMode
        set(value) {
            editor.viewMode = value
        }
    var segmentViewVoiceFilter: SegmentViewVoiceFilter
        get() = editor.segmentViewVoiceFilter
        set(value) {
            editor.segmentViewVoiceFilter = value
        }
    val segments get() = editor.segments
    val voiceMapping get() = editor.voiceMapping
    val validationResult: ValidationResult? get() = editor.validationResult
    val hasMarkers: Boolean get() = editor.hasMarkers
    val detectedVoices: Set<String> get() = editor.detectedVoices

    var selectedVoice
        get() = editor.selectedVoice
        set(value) {
            editor.selectedVoice = value
        }
    var selectedFormat
        get() = editor.selectedFormat
        set(value) {
            editor.selectedFormat = value
        }
    var speed
        get() = editor.speed
        set(value) {
            editor.speed = value
        }
    var pitchShift
        get() = editor.pitchShift
        set(value) {
            editor.pitchShift = value
        }
    var selectedRole
        get() = editor.selectedRole
        set(value) {
            editor.selectedRole = value
        }

    val playerReady get() = player.playerReady
    val playerIsPlaying get() = player.playerIsPlaying
    val playerPositionMs get() = player.playerPositionMs
    val playerDurationMs get() = player.playerDurationMs
    val chapterPlayer get() = player.chapterPlayer

    val audiobookExportValidationError get() = dialogs.audiobookExportValidationError
    val audiobookExportDialogKey get() = dialogs.audiobookExportDialogKey
    val audiobookExportBlockedRows get() = dialogs.audiobookExportBlockedRows

    fun cancelMarkupProgress() {
        markupProgressJob?.cancel()
    }

    private fun addRemarkupNeeded(chapterId: String, indices: Collection<Int>) {
        if (indices.isEmpty()) return
        val cur = remarkupNeededParagraphs[chapterId].orEmpty()
        remarkupNeededParagraphs = remarkupNeededParagraphs + (chapterId to (cur + indices))
    }

    private fun removeRemarkupNeeded(chapterId: String, indices: Collection<Int>) {
        if (indices.isEmpty()) return
        val cur = remarkupNeededParagraphs[chapterId].orEmpty() - indices.toSet()
        remarkupNeededParagraphs =
            if (cur.isEmpty()) remarkupNeededParagraphs - chapterId
            else remarkupNeededParagraphs + (chapterId to cur)
    }

    private fun clearRemarkupNeededForChapter(chapterId: String) {
        if (chapterId in remarkupNeededParagraphs) {
            remarkupNeededParagraphs = remarkupNeededParagraphs - chapterId
        }
    }

    fun saveCurrentChapter() = library.saveCurrentChapter()

    fun switchToChapter(id: String) {
        library.switchToChapter(id)
        statusMessage = ""
    }

    fun createChapter(name: String) = library.createChapter(name)
    fun renameCurrentChapter(name: String) = library.renameCurrentChapter(name)
    fun deleteCurrentChapter() = library.deleteCurrentChapter()

    fun clearAll() {
        library.clearAll()
        synthesisBackend = SessionStorage.synthesisBackend
        localTtsSettings = SessionStorage.localTtsSettings
    }

    fun saveCurrentBook() = library.saveCurrentBook()
    fun saveBook(bookName: String) = library.saveBook(bookName)
    fun loadBook(bookId: String) = library.loadBook(bookId)
    fun importFb2() = library.importFb2()
    fun importEpub() = library.importEpub()

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

    fun synthesizeAudio(text: String, settings: VoiceSettings, format: String) =
        SpeechSynthesizer.synthesize(
            text = text,
            voiceSettings = settings,
            outputFormat = format,
            backend = synthesisBackend,
            localSettings = localTtsSettings,
            cloudToken = TokenStorage.iamToken,
        )

    fun refreshChapters() = library.refreshChapters()

    fun audioFileExistsForChapter(id: String) = library.audioFileExistsForChapter(id)

    fun setChapterMarkupDoneFlag(id: String, done: Boolean) =
        library.setChapterMarkupDoneFlag(id, done)

    fun setChapterVoiceDoneFlag(id: String, done: Boolean) =
        library.setChapterVoiceDoneFlag(id, done)

    fun toggleCurrentChapterMarkupDone() = library.toggleCurrentChapterMarkupDone()
    fun toggleCurrentChapterVoiceDone() = library.toggleCurrentChapterVoiceDone()

    fun dismissFolderIdDialog() {
        dialogs.showFolderIdDialog = false
        pendingAutoMarkupWork = null
    }

    fun onFolderIdSaved(folderId: String) {
        TokenStorage.folderId = folderId
        dialogs.showFolderIdDialog = false
        val (ids, mode) = pendingAutoMarkupWork
            ?: (listOf(library.currentChapterId) to AutoMarkupMode.FillMissing)
        pendingAutoMarkupWork = null
        executeAutoMarkupForChapters(ids, mode)
    }

    fun ensureVoiceMappings(voices: Set<String>) = editor.ensureVoiceMappings(voices)

    fun removeUnusedVoices() {
        val (_, msg) = editor.removeUnusedVoicesWithMessage()
        statusMessage = msg
    }

    fun mergeVoice(fromName: String, toName: String) {
        statusMessage = editor.mergeVoice(fromName, toName)
    }

    fun syncTextFromSegments() = editor.syncTextFromSegments()

    fun mergeSegmentWithPrevious(index: Int) {
        editor.mergeSegmentWithPrevious(index)?.let { statusMessage = it }
    }

    fun mergeSegmentWithNext(index: Int) {
        editor.mergeSegmentWithNext(index)?.let { statusMessage = it }
    }

    fun resetMarkup() {
        editor.resetMarkup()
        dialogs.showResetMarkupDialog = false
    }

    fun revalidate() = editor.revalidate()

    fun syncSegmentsFromText(previousMarkupForIncremental: String? = null) =
        editor.syncSegmentsFromText(previousMarkupForIncremental)

    /** Быстрая инкрементальная валидация при правке разметки (без debounce). */
    fun applyMarkupTextChange(newText: String) {
        val prev = editor.text
        if (prev == newText) return
        editor.text = newText
        editor.syncSegmentsFromText(previousMarkupForIncremental = prev)
    }

    fun updateOriginalText(newOriginal: String) = editor.updateOriginalText(newOriginal)

    fun saveVoiceMapping() = editor.saveVoiceMapping()

    fun clearCache() {
        SessionStorage.clearChapterCache(library.currentChapterId)
        statusMessage = "Кэш очищен"
    }

    fun togglePlayerPlayPause() = player.togglePlayerPlayPause()
    fun seekPlayer(posMs: Long) = player.seekPlayer(posMs)
    fun resetPlayerState() = player.resetPlayerState()

    // ── Audiobook export ──────────────────────────────────────────────────────

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

    fun dismissAudiobookExportBlockedDialog() {
        dialogs.showAudiobookExportBlockedDialog = false
        dialogs.clearAudiobookExportBlockedRows()
    }

    fun dismissAudiobookExportDialog() {
        dialogs.showAudiobookExportDialog = false
        dialogs.audiobookExportValidationError = ""
    }

    fun defaultAudiobookExportSelection(): Set<String> {
        val list = SessionStorage.listChapters()
        return list
            .filter { chapterAudiobookExportIssues(it).isEmpty() && !it.exported }
            .map { it.id }
            .toSet()
    }

    fun validateAudiobookExportSelection(selectedIds: List<String>): String? {
        library.refreshChapters()
        val ids = selectedIds.distinct()
        if (ids.isEmpty()) return "Отметьте хотя бы одну главу"
        val order = library.chapters.map { it.id }
        for (id in ids) {
            val ch = library.chapters.find { it.id == id } ?: return "Неизвестная глава"
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
                val mid = library.chapters[k]
                if (!mid.exported) {
                    return "Между «${library.chapters[a].name}» и «${library.chapters[b].name}» глава «${mid.name}» " +
                        "ещё не в экспорте. Добавьте её в выбор или сначала экспортируйте промежуточные главы."
                }
            }
        }
        return null
    }

    fun submitAudiobookExport(selectedIds: List<String>) {
        library.refreshChapters()
        dialogs.audiobookExportValidationError = ""
        val err = validateAudiobookExportSelection(selectedIds)
        if (err != null) {
            dialogs.audiobookExportValidationError = err
            return
        }
        dialogs.showAudiobookExportDialog = false
        scope.launch { runAudiobookExportForChapterIds(selectedIds) }
    }

    fun requestAudiobookExport() {
        library.refreshChapters()
        if (library.chapters.isEmpty()) {
            dialogs.assignAudiobookExportBlockedRows(
                listOf("Книга" to listOf("нет ни одной главы")),
            )
            dialogs.showAudiobookExportBlockedDialog = true
            return
        }
        dialogs.audiobookExportValidationError = ""
        dialogs.bumpAudiobookExportDialogKey()
        dialogs.showAudiobookExportDialog = true
    }

    private suspend fun runAudiobookExportForChapterIds(chapterIds: List<String>) {
        library.refreshChapters()
        val order = library.chapters.map { it.id }
        val toExport = chapterIds.distinct().sortedBy { order.indexOf(it) }
        val parentDir = withContext(Dispatchers.IO) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите папку для экспорта аудиокниги"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
        if (parentDir == null) return

        val bookLabel = library.currentBookName.ifBlank { "Аудиокнига" }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val folderName = sanitizeAudioFileNamePart(bookLabel).ifBlank { "Аудиокнига" }
                val destRoot = File(parentDir, folderName)
                destRoot.mkdirs()
                if (!destRoot.isDirectory) {
                    error("Не удалось создать папку: ${destRoot.absolutePath}")
                }
                val numWidth = maxOf(2, library.chapters.size.toString().length)
                for (chId in toExport) {
                    val ch = library.chapters.find { it.id == chId }
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
        library.refreshChapters()
        statusMessage = result.fold(
            onSuccess = { "Экспортировано глав: ${toExport.size} → $it" },
            onFailure = { "Ошибка экспорта: ${it.message ?: it.javaClass.simpleName}" },
        )
    }

    // ── Synthesis ─────────────────────────────────────────────────────────────

    private data class MultiVoiceSynthResult(
        val savedPath: String?,
        val segmentCount: Int,
        val partsMerged: Int,
        val segmentErrors: List<String>,
    )

    fun launchSimpleSynthesis() {
        val settings = editor.voiceMapping["voice_main"] ?: VoiceSettings()
        val outputFormat = editor.selectedFormat
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = "Синтез речи\nголос: ${settings.voice}"
            try {
                SpeechSynthesizer.synthesize(
                    text = editor.text,
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
                            val chapterName = library.chapters.find { it.id == library.currentChapterId }?.name ?: ""
                            val filePath = saveAudioFile(
                                result.bytes,
                                outputFormat,
                                library.currentBookName,
                                chapterName,
                            )
                            SessionStorage.setChapterAudioPath(library.currentChapterId, filePath)
                            library.replaceCurrentChapterAudioPath(filePath)
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
            val settings = editor.voiceMapping["voice_main"] ?: VoiceSettings()
            val outputFormat = if (synthesisBackend == SynthesisBackend.Local) editor.selectedFormat else "mp3"
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
                            val chapterName = library.chapters.find { it.id == chapterId }?.name ?: ""
                            val path = saveAudioFile(
                                result.bytes,
                                outputFormat,
                                library.currentBookName,
                                chapterName,
                            )
                            SessionStorage.setChapterAudioPath(chapterId, path)
                            if (chapterId == library.currentChapterId) {
                                library.replaceCurrentChapterAudioPath(path)
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
        val outputFormat = if (isLocal) editor.selectedFormat else "mp3"
        val partExt = outputFormat
        val cacheDir = withContext(Dispatchers.IO) {
            SessionStorage.getChapterCacheDir(chapterId)
        }
        val errors = mutableListOf<String>()

        for ((index, segment) in segmentsList.withIndex()) {
            val settings = if (segment.voiceName != null) {
                editor.voiceMapping[segment.voiceName] ?: VoiceSettings()
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
        val chapterName = library.chapters.find { it.id == chapterId }?.name ?: ""
        val filePath = saveAudioFile(combined, outputFormat, library.currentBookName, chapterName)
        SessionStorage.setChapterAudioPath(chapterId, filePath)
        if (chapterId == library.currentChapterId) {
            library.replaceCurrentChapterAudioPath(filePath)
        }
        return MultiVoiceSynthResult(filePath, segmentsList.size, allParts.size, errors)
    }

    fun launchMultiVoiceSynthesis(retryVoice: String? = null) {
        val segmentsList = TextParser.parse(editor.text)
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
                    chapterId = library.currentChapterId,
                    chapterText = editor.text,
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
                library.refreshChapters()
                val lines = mutableListOf<String>()
                var ok = 0
                for ((idx, id) in distinct.withIndex()) {
                    val label = library.chapters.find { it.id == id }?.name ?: id
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
                library.refreshChapters()
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

    fun launchAutoMarkup(mode: AutoMarkupMode) {
        launchAutoMarkupForChapters(listOf(library.currentChapterId), mode)
    }

    fun launchAutoMarkupForChapters(
        chapterIds: List<String>,
        mode: AutoMarkupMode = AutoMarkupMode.FillMissing,
    ) {
        val distinct = chapterIds.distinct().filter { id ->
            SessionStorage.getChapterText(id).isNotBlank()
        }
        if (distinct.isEmpty() || isLoading) return
        val llmConfig = TokenStorage.llmConfig
        if (llmConfig.isConfigured) {
            launchAutoMarkupWithLlm(llmConfig, distinct, mode)
            return
        }

        if (TokenStorage.folderId.isBlank()) {
            pendingAutoMarkupWork = distinct to mode
            dialogs.showFolderIdDialog = true
            return
        }
        executeAutoMarkupForChapters(distinct, mode)
    }

    private fun executeAutoMarkupForChapters(chapterIds: List<String>, mode: AutoMarkupMode) {
        if (chapterIds.isEmpty() || isLoading) return
        markupProgressJob?.cancel()
        isLoading = true
        progressCancellable = true
        progressMessage = "Авто-разметка..."
        statusMessage = ""
        markupProgressJob = scope.launch {
            try {
                saveCurrentChapter()
                val plan = buildAutoMarkupJobPlan(chapterIds, mode, null)
                if (plan == null) {
                    statusMessage = "Авто-разметка: нечего размечать"
                    return@launch
                }
                val progress = AutoMarkupProgressTracker(chapterIds.size, plan)
                progressMessage = progress.formatLine(0, "")
                val errors = mutableListOf<String>()
                val voicesAcc = SessionStorage.voiceMapping.keys.toMutableSet()
                for ((index, id) in chapterIds.withIndex()) {
                    val raw = SessionStorage.getChapterText(id)
                    if (raw.isBlank()) continue
                    try {
                        runIncrementalMarkupChapter(
                            chapterId = id,
                            mode = mode,
                            chapterIndex = index,
                            totalChapters = chapterIds.size,
                            errors = errors,
                            voicesAcc = voicesAcc,
                            llmConfig = null,
                            progress = progress,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val label = library.chapters.find { it.id == id }?.name ?: id
                        errors.add("$label: ${e.message}")
                        e.printStackTrace()
                    }
                }
                library.refreshChapters()
                statusMessage = when {
                    errors.isEmpty() ->
                        "Авто-разметка: готово (${chapterIds.size} ${chapterWord(chapterIds.size)})"
                    else ->
                        "Авто-разметка: частично или с ошибками (${errors.size})\n${errors.joinToString("\n")}"
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

    private data class AutoMarkupJobPlan(val totalParagraphs: Int, val totalHttpCalls: Int)

    private class AutoMarkupProgressTracker(
        private val totalChapters: Int,
        private val plan: AutoMarkupJobPlan,
    ) {
        var httpCompleted = 0
            private set
        var paragraphsCompleted = 0
            private set

        fun onHttpCompleted() {
            httpCompleted++
        }

        fun onParagraphsCompleted(n: Int) {
            paragraphsCompleted += n
        }

        fun formatLine(chapterIndex: Int, detail: String): String {
            val hp = plan.totalHttpCalls
            val h = httpCompleted
            val httpPart = if (h <= hp) "$h/$hp" else "$h/$hp+"
            return buildString {
                append("Авто-разметка")
                if (totalChapters > 1) {
                    append(" · глава ")
                    append(chapterIndex + 1)
                    append("/")
                    append(totalChapters)
                }
                append(" · запросы ")
                append(httpPart)
                append(" · абзацы ")
                append(paragraphsCompleted)
                append("/")
                append(plan.totalParagraphs)
                if (detail.isNotBlank()) {
                    append(" · ")
                    append(detail)
                }
            }
        }
    }

    private fun buildAutoMarkupJobPlan(
        chapterIds: List<String>,
        mode: AutoMarkupMode,
        llmConfig: LlmConfig?,
    ): AutoMarkupJobPlan? {
        val chunkLimit = llmConfig?.markupChunkChars ?: AiMarkupApi.DEFAULT_YANDEX_MARKUP_CHUNK_CHARS
        var totalParas = 0
        var totalHttp = 0
        for (id in chapterIds) {
            if (SessionStorage.getChapterText(id).isBlank()) continue
            val rows = AutoMarkupParagraphPlanner.rowsOrSingleFallback(
                SessionStorage.listChapterParagraphs(id),
                SessionStorage.getOriginalText(id),
                SessionStorage.getChapterText(id),
            )
            if (rows.isEmpty()) continue
            val originals = rows.map { it.originalText }
            val working = rows.map { it.markedText }
            val indices = AutoMarkupParagraphPlanner.paragraphIndicesToProcess(mode, rows)
            if (indices.isEmpty()) continue
            totalParas += indices.size
            totalHttp += AutoMarkupParagraphPlanner.estimateHttpCallsForChapter(
                indices, chunkLimit, originals, working, mode,
            )
        }
        if (totalParas == 0) return null
        return AutoMarkupJobPlan(totalParas, totalHttp.coerceAtLeast(1))
    }

    private fun chapterWord(n: Int): String =
        when {
            n % 10 == 1 && n % 100 != 11 -> "глава"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "главы"
            else -> "глав"
        }

    private fun launchAutoMarkupWithLlm(config: LlmConfig, chapterIds: List<String>, mode: AutoMarkupMode) {
        if (chapterIds.isEmpty() || isLoading) return
        markupProgressJob?.cancel()
        isLoading = true
        progressCancellable = true
        progressMessage = "Авто-разметка (${config.model})..."
        statusMessage = ""
        markupProgressJob = scope.launch {
            try {
                saveCurrentChapter()
                val plan = buildAutoMarkupJobPlan(chapterIds, mode, config)
                if (plan == null) {
                    statusMessage = "Авто-разметка: нечего размечать"
                    return@launch
                }
                val progress = AutoMarkupProgressTracker(chapterIds.size, plan)
                progressMessage = progress.formatLine(0, "")
                val errors = mutableListOf<String>()
                val voicesAcc = SessionStorage.voiceMapping.keys.toMutableSet()
                for ((index, id) in chapterIds.withIndex()) {
                    val raw = SessionStorage.getChapterText(id)
                    if (raw.isBlank()) continue
                    try {
                        runIncrementalMarkupChapter(
                            chapterId = id,
                            mode = mode,
                            chapterIndex = index,
                            totalChapters = chapterIds.size,
                            errors = errors,
                            voicesAcc = voicesAcc,
                            llmConfig = config,
                            progress = progress,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val label = library.chapters.find { it.id == id }?.name ?: id
                        errors.add("$label: ${e.message}")
                        e.printStackTrace()
                    }
                }
                library.refreshChapters()
                statusMessage = when {
                    errors.isEmpty() ->
                        "Авто-разметка: готово (${chapterIds.size} ${chapterWord(chapterIds.size)}) (${config.model})"
                    else ->
                        "Авто-разметка: частично или с ошибками (${errors.size})\n${errors.joinToString("\n")}"
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

    private suspend fun runIncrementalMarkupChapter(
        chapterId: String,
        mode: AutoMarkupMode,
        chapterIndex: Int,
        totalChapters: Int,
        errors: MutableList<String>,
        voicesAcc: MutableSet<String>,
        llmConfig: LlmConfig?,
        progress: AutoMarkupProgressTracker?,
    ) {
        yield()
        clearRemarkupNeededForChapter(chapterId)
        val rows = AutoMarkupParagraphPlanner.rowsOrSingleFallback(
            SessionStorage.listChapterParagraphs(chapterId),
            SessionStorage.getOriginalText(chapterId),
            SessionStorage.getChapterText(chapterId),
        )
        if (rows.isEmpty()) return
        val originals = rows.map { it.originalText }
        val working = rows.map { it.markedText }.toMutableList()
        val indices = AutoMarkupParagraphPlanner.paragraphIndicesToProcess(mode, rows)
        if (indices.isEmpty()) return

        val chunkLimit = llmConfig?.markupChunkChars ?: AiMarkupApi.DEFAULT_YANDEX_MARKUP_CHUNK_CHARS
        val token = TokenStorage.iamToken
        val folderId = TokenStorage.folderId

        val greedyBatchesInChapter = AutoMarkupParagraphPlanner.estimateGreedyBatchCountForChapter(
            indices, chunkLimit, originals, working, mode,
        ).coerceAtLeast(1)

        suspend fun callMarkupChunk(joined: String, systemPrompt: String, detailAfter: String): String {
            val marked = if (llmConfig != null) {
                OpenAiMarkupApi.markupChunkForPrompt(joined, llmConfig, systemPrompt)
            } else {
                AiMarkupApi.markupChunkForPrompt(joined, token, folderId, systemPrompt)
            }
            progress?.apply {
                onHttpCompleted()
                progressMessage = formatLine(chapterIndex, detailAfter)
            }
            return marked
        }

        suspend fun persistAfterBatch(
            fingerprints: List<Pair<Int, String>>,
            detailAfter: String,
        ) {
            val pairs = originals.zip(working) { o, m -> o to m }
            SessionStorage.replaceAllParagraphsForChapter(chapterId, pairs)
            for ((paraIdx, fp) in fingerprints) {
                SessionStorage.saveAutoMarkupSourceFingerprint(chapterId, rows[paraIdx].ordinal, fp)
            }
            removeRemarkupNeeded(chapterId, fingerprints.map { it.first })
            mergeDiscoveredVoicesIntoBook(voicesAcc)
            applyChapterMarkupToEditorIfCurrent(chapterId, originals, working, voicesAcc)
            progress?.apply {
                onParagraphsCompleted(fingerprints.size)
                progressMessage = formatLine(chapterIndex, detailAfter)
            }
        }

        suspend fun trySingleParagraphSubchunks(
            i: Int,
            sourceFull: String,
            fingerprintHex: String,
            contextDetail: String,
        ): Exception? {
            val chunks = AutoMarkupParagraphPlanner.splitParagraphChunks(sourceFull, chunkLimit)
            val subResults = mutableListOf<String>()
            for ((ci, chunk) in chunks.withIndex()) {
                yield()
                val partDetail =
                    if (chunks.size > 1) "$contextDetail · часть ${ci + 1}/${chunks.size}" else contextDetail
                progressMessage = progress?.formatLine(chapterIndex, partDetail) ?: partDetail
                val systemPrompt = MarkupSystemPrompts.autoMarkupPrompt(voicesAcc)
                try {
                    val marked = callMarkupChunk(chunk, systemPrompt, partDetail)
                    subResults.add(marked)
                    voicesAcc.addAll(TextParser.extractVoiceNames(marked))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return e
                }
            }
            val merged = subResults.joinToString(" ").trim()
            val order = AutoMarkupOrderCheck.verify(sourceFull, merged)
            if (!order.ok) {
                addRemarkupNeeded(chapterId, listOf(i))
                return Exception(
                    "разметка отклонена: нарушен порядок слов относительно исходника " +
                        "(${(order.matchRatio * 100).toInt()}%, порог 88%)",
                )
            }
            working[i] = merged
            persistAfterBatch(listOf(i to fingerprintHex), contextDetail)
            return null
        }

        suspend fun processBatchWithFallback(batch: List<Int>, packDetail: String) {
            val sources = batch.map {
                AutoMarkupParagraphPlanner.sourceTextForAi(originals[it], working[it], mode).trim()
            }
            val fingerprints = batch.mapIndexed { k, paraIdx ->
                paraIdx to AutoMarkupFingerprint.sha256Hex(sources[k])
            }
            val joined = TextParser.joinParagraphsForStorage(sources)

            val systemPrompt = MarkupSystemPrompts.autoMarkupPrompt(voicesAcc)
            try {
                if (batch.size == 1) {
                    val i = batch[0]
                    if (joined.length <= chunkLimit) {
                        val marked = callMarkupChunk(joined, systemPrompt, packDetail).trim()
                        val ord = AutoMarkupOrderCheck.verify(sources[0], marked)
                        if (!ord.ok) {
                            val label = library.chapters.find { it.id == chapterId }?.name ?: chapterId
                            errors.add(
                                "$label, $packDetail: разметка отклонена — нарушен порядок слов " +
                                    "(${(ord.matchRatio * 100).toInt()}%, порог 88%). Переразметьте абзац ${i + 1}.",
                            )
                            addRemarkupNeeded(chapterId, batch)
                        } else {
                            voicesAcc.addAll(TextParser.extractVoiceNames(marked))
                            working[i] = marked
                            persistAfterBatch(listOf(i to fingerprints[0].second), packDetail)
                        }
                    } else {
                        trySingleParagraphSubchunks(i, sources[0], fingerprints[0].second, packDetail)?.let { throw it }
                    }
                    return
                }

                val markedJoined = callMarkupChunk(joined, systemPrompt, packDetail)
                voicesAcc.addAll(TextParser.extractVoiceNames(markedJoined))
                val aligned = AutoMarkupBatchAlign.alignOrNull(sources, markedJoined)
                if (aligned != null) {
                    val orderBad = batch.indices.filter {
                        !AutoMarkupOrderCheck.verify(sources[it], aligned[it].trim()).ok
                    }
                    if (orderBad.isNotEmpty()) {
                        val label = library.chapters.find { it.id == chapterId }?.name ?: chapterId
                        val nums = orderBad.map { batch[it] + 1 }.sorted().joinToString(", ")
                        val detail = orderBad.joinToString("; ") { ix ->
                            val o = AutoMarkupOrderCheck.verify(sources[ix], aligned[ix].trim())
                            "абз. ${batch[ix] + 1}: ${(o.matchRatio * 100).toInt()}%"
                        }
                        errors.add(
                            "$label, $packDetail: ответ нельзя принять — нарушен порядок слов в абзацах $nums ($detail). " +
                                "Переразметьте эти абзацы.",
                        )
                        addRemarkupNeeded(chapterId, batch)
                    } else {
                        for (k in batch.indices) {
                            val piece = aligned[k].trim()
                            working[batch[k]] = piece
                            voicesAcc.addAll(TextParser.extractVoiceNames(piece))
                        }
                        persistAfterBatch(fingerprints, packDetail)
                    }
                } else {
                    val label = library.chapters.find { it.id == chapterId }?.name ?: chapterId
                    val nums = batch.map { it + 1 }.sorted().joinToString(", ")
                    errors.add(
                        "$label, $packDetail: ответ модели не сопоставился с абзацами $nums. " +
                            "Абзацы подсвечены — переразметьте их отдельно (режим по абзацам или повтор авторазметки).",
                    )
                    addRemarkupNeeded(chapterId, batch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val label = library.chapters.find { it.id == chapterId }?.name ?: chapterId
                errors.add("$label, пакет $packDetail: ${e.message}")
            }
        }

        val runs = AutoMarkupParagraphPlanner.consecutiveIndexRuns(indices.sorted())
        var batchNum = 0
        for (run in runs) {
            var pending = run.toMutableList()
            while (pending.isNotEmpty()) {
                yield()
                val planned = AutoMarkupParagraphPlanner.greedyBatchesWithinRun(
                    pending,
                    chunkLimit,
                    originals,
                    working,
                    mode,
                )
                if (planned.isEmpty()) {
                    pending.removeAt(0)
                    continue
                }
                val batch = planned[0]
                batchNum++
                val a = batch.minOf { it } + 1
                val b = batch.maxOf { it } + 1
                val batchLabel = if (batch.size == 1) "абз. $a" else "абз. $a–$b (${batch.size} шт.)"
                val packDetail = "пакет $batchNum/$greedyBatchesInChapter · $batchLabel"
                progressMessage = progress?.formatLine(chapterIndex, packDetail) ?: packDetail
                processBatchWithFallback(batch, packDetail)
                pending.removeAll { it in batch.toSet() }
            }
        }
    }

    private fun mergeDiscoveredVoicesIntoBook(voicesAcc: Set<String>) {
        val cur = SessionStorage.voiceMapping.toMutableMap()
        var changed = false
        for (v in voicesAcc) {
            if (v !in cur) {
                cur[v] = VoiceSettings()
                changed = true
            }
        }
        if (changed) SessionStorage.voiceMapping = cur
    }

    private fun applyChapterMarkupToEditorIfCurrent(
        chapterId: String,
        originals: List<String>,
        working: List<String>,
        voicesAcc: Set<String>,
    ) {
        if (chapterId != library.currentChapterId) return
        editor.text = TextParser.joinParagraphsForStorage(working)
        editor.originalText = TextParser.joinParagraphsForStorage(originals)
        editor.markupModeEnabled = true
        editor.ensureVoiceMappings(voicesAcc)
        editor.saveVoiceMapping()
        editor.revalidate()
    }

    /**
     * Переразметка одного абзаца главы (тот же путь, что и авторазметка по частям), после сбоя сопоставления батча.
     * [paragraphIndex] — 0-based, как строки в редакторе / [TextParser.splitParagraphsForStorage].
     */
    fun remarkupChapterParagraph(paragraphIndex: Int) {
        if (isLoading) return
        val chapterId = library.currentChapterId
        val llmConfig = TokenStorage.llmConfig
        val config = llmConfig.takeIf { it.isConfigured }
        if (config == null && TokenStorage.folderId.isBlank()) {
            dialogs.showFolderIdDialog = true
            return
        }
        markupProgressJob?.cancel()
        isLoading = true
        progressCancellable = true
        progressMessage = "Переразметка абзаца ${paragraphIndex + 1}..."
        markupProgressJob = scope.launch {
            try {
                val rows = AutoMarkupParagraphPlanner.rowsOrSingleFallback(
                    SessionStorage.listChapterParagraphs(chapterId),
                    SessionStorage.getOriginalText(chapterId),
                    SessionStorage.getChapterText(chapterId),
                )
                if (paragraphIndex !in rows.indices) {
                    statusMessage = "Абзац не найден"
                    return@launch
                }
                val originals = rows.map { it.originalText }
                val working = rows.map { it.markedText }.toMutableList()
                val mode = AutoMarkupMode.FullRemark
                val i = paragraphIndex
                val sourceFull = AutoMarkupParagraphPlanner.sourceTextForAi(originals[i], working[i], mode).trim()
                if (sourceFull.isBlank()) {
                    statusMessage = "Нет текста для разметки"
                    return@launch
                }
                val chunkLimit = config?.markupChunkChars ?: AiMarkupApi.DEFAULT_YANDEX_MARKUP_CHUNK_CHARS
                val token = TokenStorage.iamToken
                val folderId = TokenStorage.folderId
                val voicesAcc = SessionStorage.voiceMapping.keys.toMutableSet()
                val chunks =
                    if (sourceFull.length <= chunkLimit) listOf(sourceFull)
                    else AutoMarkupParagraphPlanner.splitParagraphChunks(sourceFull, chunkLimit)
                val subResults = mutableListOf<String>()
                for ((ci, chunk) in chunks.withIndex()) {
                    yield()
                    val partDetail =
                        if (chunks.size > 1) "абз. ${i + 1}, часть ${ci + 1}/${chunks.size}" else "абз. ${i + 1}"
                    progressMessage = partDetail
                    val systemPrompt = MarkupSystemPrompts.autoMarkupPrompt(voicesAcc)
                    val marked = if (config != null) {
                        OpenAiMarkupApi.markupChunkForPrompt(chunk, config, systemPrompt)
                    } else {
                        AiMarkupApi.markupChunkForPrompt(chunk, token, folderId, systemPrompt)
                    }
                    subResults.add(marked)
                    voicesAcc.addAll(TextParser.extractVoiceNames(marked))
                }
                val merged = subResults.joinToString(" ").trim()
                val ordCheck = AutoMarkupOrderCheck.verify(sourceFull, merged)
                if (!ordCheck.ok) {
                    addRemarkupNeeded(chapterId, setOf(i))
                    statusMessage =
                        "Абзац ${i + 1}: разметка отклонена — нарушен порядок слов " +
                            "(${(ordCheck.matchRatio * 100).toInt()}%, порог 88%). Повторите или переразметьте вручную."
                    return@launch
                }
                working[i] = merged
                val fp = AutoMarkupFingerprint.sha256Hex(sourceFull)
                val pairs = originals.zip(working) { o, m -> o to m }
                SessionStorage.replaceAllParagraphsForChapter(chapterId, pairs)
                SessionStorage.saveAutoMarkupSourceFingerprint(chapterId, rows[i].ordinal, fp)
                removeRemarkupNeeded(chapterId, setOf(i))
                mergeDiscoveredVoicesIntoBook(voicesAcc)
                applyChapterMarkupToEditorIfCurrent(chapterId, originals, working, voicesAcc)
                library.refreshChapters()
                statusMessage = "Абзац ${paragraphIndex + 1} переразмечен"
            } catch (e: CancellationException) {
                statusMessage = "Переразметка отменена"
                throw e
            } catch (e: Exception) {
                statusMessage = "Ошибка переразметки абзаца: ${e.message}"
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
            OpenAiMarkupApi.fixDialog(text = editor.segments[index].text, config = llmConfig)
        } else {
            val folderId = TokenStorage.folderId
            if (folderId.isBlank()) {
                dialogs.showFolderIdDialog = true
                return
            }
            AiMarkupApi.fixDialog(
                text = editor.segments[index].text,
                token = TokenStorage.iamToken,
                folderId = folderId,
            )
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
                                editor.segments.removeAt(index)
                                editor.segments.addAll(index, newSegments)
                                for (seg in newSegments) {
                                    val name = seg.voiceName ?: continue
                                    if (name !in editor.voiceMapping) editor.voiceMapping[name] = VoiceSettings()
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