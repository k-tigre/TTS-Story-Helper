package by.tigre.speechhelper.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.AiMarkupApi
import by.tigre.speechhelper.data.MarkupResult
import by.tigre.speechhelper.data.OpenAiMarkupApi
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.data.SpeechSynthesizer
import by.tigre.speechhelper.data.SynthesisResult
import by.tigre.speechhelper.data.WavMerge
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

    private var pendingMarkupChapterIds: List<String>? = null

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
        pendingMarkupChapterIds = null
    }

    fun onFolderIdSaved(folderId: String) {
        TokenStorage.folderId = folderId
        dialogs.showFolderIdDialog = false
        val ids = pendingMarkupChapterIds ?: listOf(library.currentChapterId)
        pendingMarkupChapterIds = null
        executeAutoMarkupForChapters(ids)
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

    fun launchAutoMarkup() {
        launchAutoMarkupForChapters(listOf(library.currentChapterId))
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
            dialogs.showFolderIdDialog = true
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
                            existingVoices = editor.voiceMapping.keys.toSet(),
                        ).collectLatest { result ->
                            when (result) {
                                is MarkupResult.InProgress ->
                                    progressMessage =
                                        "Авто-разметка ${index + 1} из ${chapterIds.size}\n${result.message}"
                                is MarkupResult.Done -> {
                                    SessionStorage.setOriginalText(id, raw)
                                    SessionStorage.setChapterText(id, result.text)
                                    if (id == library.currentChapterId) {
                                        editor.originalText = raw
                                        editor.text = result.text
                                        editor.markupModeEnabled = true
                                        editor.revalidate()
                                    }
                                }
                            }
                        }
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
                val existingVoices = editor.voiceMapping.keys.toSet()
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
                                    if (id == library.currentChapterId) {
                                        editor.originalText = raw
                                        editor.text = result.text
                                        editor.markupModeEnabled = true
                                        editor.revalidate()
                                    }
                                }
                            }
                        }
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