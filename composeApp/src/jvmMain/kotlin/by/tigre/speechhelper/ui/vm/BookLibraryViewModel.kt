package by.tigre.speechhelper.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import by.tigre.speechhelper.data.EpubParser
import by.tigre.speechhelper.data.Fb2Parser
import by.tigre.speechhelper.data.ImportApplyResult
import by.tigre.speechhelper.data.InitialSessionSnapshot
import by.tigre.speechhelper.data.ParsedBook
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.data.preparedForStorage
import by.tigre.speechhelper.domain.ChapterInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private sealed interface ImportWork {
    data object EmptyChapters : ImportWork
    data class Ok(val book: ParsedBook, val apply: ImportApplyResult) : ImportWork
}

class BookLibraryViewModel(
    private val scope: CoroutineScope,
    private val dialogs: AppDialogState,
    private val player: PlayerViewModel,
    private val editor: EditorWorkspaceViewModel,
    private val status: (String) -> Unit,
    private val setImportLoading: (Boolean) -> Unit,
    private val setProgressMessage: (String) -> Unit,
) {

    var chapters by mutableStateOf<List<ChapterInfo>>(emptyList())
        private set
    var currentChapterId by mutableStateOf("")
        private set
    var currentBookName by mutableStateOf("")
        private set

    var chapterAudioPath by mutableStateOf<String?>(null)
        private set

    /** Обновить кэш пути к файлу озвучки текущей главы (после синтеза и т.п.). */
    fun replaceCurrentChapterAudioPath(path: String?) {
        chapterAudioPath = path
    }

    fun hydrateFromInitialSnapshot(snap: InitialSessionSnapshot) {
        chapters = snap.chapters
        currentChapterId = snap.currentChapterId
        currentBookName = snap.currentBookTitle
        chapterAudioPath = snap.chapterAudioPath
    }

    fun saveCurrentChapter() {
        SessionStorage.setChapterText(currentChapterId, editor.text)
    }

    fun switchToChapter(id: String) {
        if (id == currentChapterId) return
        val snap = SessionStorage.persistSwitchChapter(currentChapterId, editor.text, id)
        player.closeForChapterSwitch()
        currentChapterId = id
        chapterAudioPath = snap.audioPath
        editor.applyChapterSwitch(snap)
    }

    fun createChapter(name: String) {
        saveCurrentChapter()
        val id = SessionStorage.createChapter(name)
        chapters = SessionStorage.listChapters()
        switchToChapter(id)
        dialogs.showCreateDialog = false
    }

    fun renameCurrentChapter(name: String) {
        SessionStorage.renameChapter(currentChapterId, name)
        chapters = SessionStorage.listChapters()
        dialogs.showRenameDialog = false
    }

    fun deleteCurrentChapter() {
        SessionStorage.deleteChapter(currentChapterId)
        chapters = SessionStorage.listChapters()
        val newId = SessionStorage.ensureCurrentChapter()
        currentChapterId = newId
        chapterAudioPath = SessionStorage.getChapterAudioPath(newId)
        editor.loadChapterContentFromStorage(newId)
        dialogs.showDeleteDialog = false
    }

    fun clearAll() {
        saveCurrentChapter()
        SessionStorage.clearAllData()
        val id = SessionStorage.ensureCurrentChapter()
        chapters = SessionStorage.listChapters()
        currentChapterId = id
        chapterAudioPath = null
        currentBookName = SessionStorage.currentBookTitle()
        editor.resetSessionAfterClearAll(
            chapterText = SessionStorage.getChapterText(id),
            original = SessionStorage.getOriginalText(id),
            voiceFromStorage = SessionStorage.voiceMapping,
        )
        dialogs.showClearAllDialog = false
        status("Всё очищено")
    }

    fun saveCurrentBook() {
        saveCurrentChapter()
        editor.saveVoiceMapping()
        if (currentBookName.isNotBlank()) {
            SessionStorage.saveBookTitle(currentBookName)
            status("Сохранено: \"${SessionStorage.currentBookTitle()}\"")
        } else {
            dialogs.showSaveBookDialog = true
        }
    }

    fun saveBook(bookName: String) {
        saveCurrentChapter()
        editor.saveVoiceMapping()
        SessionStorage.saveBookTitle(bookName)
        currentBookName = SessionStorage.currentBookTitle()
        status("Название: \"$currentBookName\"")
        dialogs.showSaveBookDialog = false
    }

    fun loadBook(bookId: String) {
        saveCurrentChapter()
        if (SessionStorage.loadBook(bookId)) {
            chapters = SessionStorage.listChapters()
            val id = SessionStorage.ensureCurrentChapter()
            currentChapterId = id
            editor.text = SessionStorage.getChapterText(id)
            editor.originalText = SessionStorage.getOriginalText(id)
            chapterAudioPath = SessionStorage.getChapterAudioPath(id)
            editor.resetVoiceMappingFromStorage(SessionStorage.voiceMapping)
            currentBookName = SessionStorage.currentBookTitle()
            editor.segmentViewVoiceFilter = SegmentViewVoiceFilter.All
            editor.markupModeEnabled = true
            status("Открыта \"$currentBookName\"")
        } else {
            status("Ошибка: не удалось открыть книгу")
        }
        dialogs.showLoadBookDialog = false
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
        setProgressMessage(message)
    }

    private suspend fun importParsedBook(label: String, parse: suspend () -> ParsedBook) {
        val chapterIdSnapshot = currentChapterId
        val textSnapshot = editor.text
        setImportLoading(true)
        postImportProgress("Чтение $label...")
        try {
            val work = withContext(Dispatchers.IO) {
                val book = parse()
                if (book.chapters.isEmpty()) {
                    return@withContext ImportWork.EmptyChapters
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
                ImportWork.Ok(book, result)
            }

            when (work) {
                ImportWork.EmptyChapters -> {
                    status("Ошибка: не найдены главы в файле")
                    return
                }
                is ImportWork.Ok -> {
                    val book = work.book
                    val result = work.apply
                    player.closeForChapterSwitch()
                    player.resetPlayerState()
                    chapters = result.chapters
                    currentChapterId = result.firstChapterId
                    val snap = result.initialEditor
                    chapterAudioPath = snap.audioPath
                    currentBookName = result.bookTitle
                    editor.applyAfterImport(snap)
                    status(
                        "Добавлено в библиотеку: \"${result.bookTitle}\" (${book.chapters.size} гл.). " +
                            "Открыть другую — «Загрузить книгу».",
                    )
                }
            }
        } catch (e: Exception) {
            status("Ошибка импорта $label: ${e.message}")
            e.printStackTrace()
        } finally {
            setImportLoading(false)
            postImportProgress("")
        }
    }

    fun refreshChapters() {
        chapters = SessionStorage.listChapters()
    }

    fun audioFileExistsForChapter(id: String): Boolean {
        val p = SessionStorage.getChapterAudioPath(id) ?: return false
        return File(p).isFile
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
}
