package by.tigre.speechhelper.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Глобальные модальные окна приложения; владеет [RootViewModel]. */
class AppDialogState {
    var showTokenDialog by mutableStateOf(false)
    var showCreateDialog by mutableStateOf(false)
    var showRenameDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    var showClearAllDialog by mutableStateOf(false)
    var showSaveBookDialog by mutableStateOf(false)
    var showLoadBookDialog by mutableStateOf(false)
    var showFolderIdDialog by mutableStateOf(false)
    var showResetMarkupDialog by mutableStateOf(false)
    /** Подтверждение переразметки: null — текущая глава из главного экрана; не null — список глав (пакет). */
    var showAutoMarkupRemarkingDialog by mutableStateOf(false)
    var autoMarkupRemarkingPendingChapterIds by mutableStateOf<List<String>?>(null)
    var showHelpDialog by mutableStateOf(false)
    var showChaptersWorkflowDialog by mutableStateOf(false)
    var showAudiobookExportBlockedDialog by mutableStateOf(false)
    var showAudiobookExportDialog by mutableStateOf(false)

    var audiobookExportDialogKey by mutableStateOf(0)
        private set
    var audiobookExportValidationError by mutableStateOf("")
    var audiobookExportBlockedRows by mutableStateOf<List<Pair<String, List<String>>>>(emptyList())
        private set

    fun bumpAudiobookExportDialogKey() {
        audiobookExportDialogKey++
    }

    fun assignAudiobookExportBlockedRows(rows: List<Pair<String, List<String>>>) {
        audiobookExportBlockedRows = rows
    }

    fun clearAudiobookExportBlockedRows() {
        audiobookExportBlockedRows = emptyList()
    }
}
