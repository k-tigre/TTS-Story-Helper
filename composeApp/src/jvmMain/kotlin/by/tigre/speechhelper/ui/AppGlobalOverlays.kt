package by.tigre.speechhelper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.ui.vm.RootViewModel

/** Глобальные диалоги; состояние — [RootViewModel.dialogs]. */
@Composable
fun AppGlobalOverlays(root: RootViewModel) {
    val vm = root.main
    val dialogs = root.dialogs

    if (dialogs.showCreateDialog) {
        CreateChapterDialog(
            onDismiss = { dialogs.showCreateDialog = false },
            onCreate = { vm.createChapter(it) },
        )
    }

    if (dialogs.showRenameDialog) {
        val currentName = vm.chapters.find { it.id == vm.currentChapterId }?.name ?: ""
        RenameChapterDialog(
            currentName = currentName,
            onDismiss = { dialogs.showRenameDialog = false },
            onRename = { vm.renameCurrentChapter(it) },
        )
    }

    if (dialogs.showDeleteDialog) {
        val currentName = vm.chapters.find { it.id == vm.currentChapterId }?.name ?: ""
        DeleteChapterDialog(
            chapterName = currentName,
            onDismiss = { dialogs.showDeleteDialog = false },
            onDelete = { vm.deleteCurrentChapter() },
        )
    }

    if (dialogs.showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { dialogs.showClearAllDialog = false },
            title = { Text("Очистить всё?") },
            text = { Text("Все главы, настройки голосов и кэш будут удалены. Это действие нельзя отменить.") },
            confirmButton = {
                Button(
                    onClick = { vm.clearAll() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogs.showClearAllDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (dialogs.showSaveBookDialog) {
        SaveBookDialog(
            onDismiss = { dialogs.showSaveBookDialog = false },
            onSave = { vm.saveBook(it) },
        )
    }

    if (dialogs.showLoadBookDialog) {
        LoadBookDialog(
            onDismiss = { dialogs.showLoadBookDialog = false },
            onLoad = { vm.loadBook(it) },
            onDelete = { SessionStorage.deleteBook(it) },
        )
    }

    if (dialogs.showFolderIdDialog) {
        FolderIdDialog(
            onDismiss = { vm.dismissFolderIdDialog() },
            onSave = { folderId -> vm.onFolderIdSaved(folderId) },
        )
    }

    if (dialogs.showChaptersWorkflowDialog) {
        ChaptersWorkflowDialog(
            chapters = vm.chapters,
            currentChapterId = vm.currentChapterId,
            isLoading = vm.isLoading,
            onDismiss = { dialogs.showChaptersWorkflowDialog = false },
            onLaunchBatchMarkup = { ids ->
                if (!TokenStorage.hasCredentials()) {
                    dialogs.showTokenDialog = true
                } else {
                    vm.launchAutoMarkupForChapters(ids)
                    dialogs.showChaptersWorkflowDialog = false
                }
            },
            onLaunchBatchSynthesis = { ids ->
                if (vm.synthesisBackend == SynthesisBackend.Cloud && !TokenStorage.hasCredentials()) {
                    dialogs.showTokenDialog = true
                } else {
                    vm.launchBatchSynthesisForChapters(ids)
                    dialogs.showChaptersWorkflowDialog = false
                }
            },
            onSetMarkupDone = { id, done -> vm.setChapterMarkupDoneFlag(id, done) },
            onSetVoiceDone = { id, done -> vm.setChapterVoiceDoneFlag(id, done) },
            audioExists = { vm.audioFileExistsForChapter(it) },
        )
    }

    if (dialogs.showAudiobookExportDialog) {
        var exportSelectedIds by remember(vm.audiobookExportDialogKey) {
            mutableStateOf(vm.defaultAudiobookExportSelection())
        }
        AudiobookExportDialog(
            chapters = vm.chapters,
            eligibilityIssues = { vm.chapterAudiobookExportEligibilityIssues(it) },
            selectedIds = exportSelectedIds,
            onSelectedIdsChange = { exportSelectedIds = it },
            validationError = vm.audiobookExportValidationError,
            onDismiss = { vm.dismissAudiobookExportDialog() },
            onExport = { vm.submitAudiobookExport(it) },
        )
    }

    if (dialogs.showTokenDialog) {
        TokenDialog(
            onDismiss = { dialogs.showTokenDialog = false },
            onSave = { token ->
                TokenStorage.iamToken = token
                dialogs.showTokenDialog = false
            },
            onOpenHelp = {
                dialogs.showTokenDialog = false
                dialogs.showHelpDialog = true
            },
        )
    }

    if (dialogs.showHelpDialog) {
        HelpDialog(onDismiss = { dialogs.showHelpDialog = false })
    }

    if (dialogs.showResetMarkupDialog) {
        AlertDialog(
            onDismissRequest = { dialogs.showResetMarkupDialog = false },
            title = { Text("Сбросить разметку?") },
            text = { Text("Все голосовые теги будут удалены. Будет оставлен только исходный текст.") },
            confirmButton = {
                Button(
                    onClick = { vm.resetMarkup() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogs.showResetMarkupDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (dialogs.showAudiobookExportBlockedDialog) {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { vm.dismissAudiobookExportBlockedDialog() },
            title = { Text("Аудиокнига ещё не готова") },
            text = {
                Column(Modifier.verticalScroll(scrollState)) {
                    Text(
                        "Чтобы экспортировать аудиокнигу, по каждой главе нужны: сохранённый файл озвучки и отмеченные флажки «Разметка готова» и «Озвучка готова». Сейчас не хватает:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    vm.audiobookExportBlockedRows.forEach { (chapterName, reasons) ->
                        val line = buildString {
                            append("• ")
                            if (chapterName.isNotBlank()) {
                                append("«")
                                append(chapterName)
                                append("»: ")
                            }
                            append(reasons.joinToString("; "))
                        }
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissAudiobookExportBlockedDialog() }) {
                    Text("Понятно")
                }
            },
        )
    }
}
