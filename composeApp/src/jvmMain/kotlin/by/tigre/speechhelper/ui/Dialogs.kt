package by.tigre.speechhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.SessionStorage

@Composable
fun TokenDialog(
    onDismiss: () -> Unit,
    onSave: (token: String) -> Unit,
) {
    var token by remember { mutableStateOf(TokenStorage.iamToken) }
    var folderId by remember { mutableStateOf(TokenStorage.folderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = folderId,
                    onValueChange = { folderId = it },
                    label = { Text("Folder ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    TokenStorage.folderId = folderId
                    onSave(token)
                },
                enabled = token.isNotBlank(),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            if (TokenStorage.hasCredentials()) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Composable
fun CreateChapterDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая глава") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Создать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun RenameChapterDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать главу") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun DeleteChapterDialog(
    chapterName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить главу?") },
        text = { Text("Глава \"$chapterName\" будет удалена вместе с кэшем. Это действие нельзя отменить.") },
        confirmButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Удалить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun SaveBookDialog(
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить книгу") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название книги") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun LoadBookDialog(
    onDismiss: () -> Unit,
    onLoad: (name: String) -> Unit,
    onDelete: (name: String) -> Unit,
) {
    var books by remember { mutableStateOf(SessionStorage.listBooks()) }
    var confirmDeleteBook by remember { mutableStateOf<String?>(null) }

    if (confirmDeleteBook != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteBook = null },
            title = { Text("Удалить книгу?") },
            text = { Text("Книга \"$confirmDeleteBook\" будет удалена.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(confirmDeleteBook!!)
                        confirmDeleteBook = null
                        books = SessionStorage.listBooks()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBook = null }) { Text("Отмена") }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Загрузить книгу") },
        text = {
            if (books.isEmpty()) {
                Text("Нет сохранённых книг")
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    books.forEach { bookName ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(
                                onClick = { onLoad(bookName) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = bookName, modifier = Modifier.fillMaxWidth())
                            }
                            IconButton(onClick = { confirmDeleteBook = bookName }) {
                                Text(
                                    "\u2716",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
fun ProgressDialog(progressMessage: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                if (progressMessage.isNotBlank()) {
                    Text(
                        text = progressMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun SplitSegmentDialog(
    segmentText: String,
    voiceName: String?,
    onDismiss: () -> Unit,
    onSplit: (parts: List<String>) -> Unit,
) {
    var editedText by remember { mutableStateOf(segmentText) }
    val partsCount = editedText.split("===").count { it.trim().isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разбить сегмент") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Вставьте === в местах разбиения" +
                            if (voiceName != null) "\nГолос: $voiceName (сохранится для всех частей)" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (partsCount > 1) {
                    Text(
                        text = "Будет создано частей: $partsCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parts = editedText.split("===").map { it.trim() }.filter { it.isNotBlank() }
                    if (parts.size > 1) onSplit(parts)
                },
                enabled = partsCount > 1,
            ) {
                Text("Разбить ($partsCount)")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun FolderIdDialog(
    onDismiss: () -> Unit,
    onSave: (folderId: String) -> Unit,
) {
    var folderIdInput by remember { mutableStateOf(TokenStorage.folderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder ID") },
        text = {
            OutlinedTextField(
                value = folderIdInput,
                onValueChange = { folderIdInput = it },
                label = { Text("Идентификатор каталога Yandex Cloud") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(folderIdInput) },
                enabled = folderIdInput.isNotBlank(),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
