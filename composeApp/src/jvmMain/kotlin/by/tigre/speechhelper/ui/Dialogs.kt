package by.tigre.speechhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.SessionStorage
import java.awt.Desktop
import java.net.URI

@Composable
fun TokenDialog(
    onDismiss: () -> Unit,
    onSave: (token: String) -> Unit,
    onOpenHelp: () -> Unit = {},
) {
    var token by remember { mutableStateOf(TokenStorage.iamToken) }
    var folderId by remember { mutableStateOf(TokenStorage.folderId) }
    val linkColor = Color(0xFF1976D2)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Key") },
                    supportingText = {
                        Text("Ключ авторизации для Yandex SpeechKit. Используется для синтеза речи.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = folderId,
                    onValueChange = { folderId = it },
                    label = { Text("Folder ID") },
                    supportingText = {
                        Text("Идентификатор каталога в Yandex Cloud. Необходим для авто-разметки (AI).")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextButton(onClick = onOpenHelp) {
                    Text(
                        "Как получить API Key и Folder ID?",
                        color = linkColor,
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                    )
                }
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
            TextButton(onClick = onDismiss) { Text("Отмена") }
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

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val linkColor = Color(0xFF1976D2)
    val links = mapOf(
        "link_api_key" to "https://yandex.cloud/ru/docs/iam/operations/api-key/create",
        "link_folder_id" to "https://yandex.cloud/ru/docs/resource-manager/operations/folder/get-id",
        "link_speechkit" to "https://yandex.cloud/ru/docs/speechkit/",
        "link_console" to "https://console.yandex.cloud/",
    )

    val annotatedText = buildAnnotatedString {
        // ── Описание ──
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("О программе\n")
        }
        append("SpeechHelper — приложение для озвучивания текстов с помощью Yandex SpeechKit. ")
        append("Поддерживает озвучивание одним голосом и многоголосую озвучку с автоматической разметкой диалогов.\n\n")

        // ── Настройка ──
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("1. Настройка учётных данных\n")
        }
        append("Для работы приложения необходимы API Key и Folder ID из Yandex Cloud.\n\n")

        append("Как получить API Key:\n")
        append("  1. Зарегистрируйтесь или войдите в ")
        pushStringAnnotation("url", links["link_console"]!!)
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Yandex Cloud Console")
        }
        pop()
        append("\n  2. Создайте сервисный аккаунт или используйте существующий\n")
        append("  3. Создайте API-ключ по инструкции: ")
        pushStringAnnotation("url", links["link_api_key"]!!)
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Создание API-ключа")
        }
        pop()
        append("\n\n")

        append("Как получить Folder ID:\n")
        append("  1. Откройте Yandex Cloud Console\n")
        append("  2. Перейдите в нужный каталог (folder)\n")
        append("  3. Идентификатор указан на странице каталога. Подробнее: ")
        pushStringAnnotation("url", links["link_folder_id"]!!)
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Получение Folder ID")
        }
        pop()
        append("\n\n")

        append("Введите полученные данные через кнопку ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("\u2699") }
        append(" (шестерёнка) в правом верхнем углу.\n\n")

        // ── Алгоритм работы ──
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("2. Алгоритм работы\n")
        }

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("Простая озвучка (один голос):\n")
        }
        append("  1. Вставьте или напишите текст в редакторе\n")
        append("  2. На панели справа выберите голос, роль (интонацию), скорость и высоту тона\n")
        append("  3. Нажмите «Озвучить» — файл сохранится в ~/SpeechHelper/\n\n")

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("Многоголосая озвучка:\n")
        }
        append("  1. Вставьте текст в редактор\n")
        append("  2. Нажмите «Авто-разметка» — AI расставит голосовые теги для персонажей и рассказчика\n")
        append("  3. Проверьте разметку во вкладке «Текст» (левая колонка — исходный текст, правая — разметка)\n")
        append("  4. Нажмите «Проверить» для валидации (зелёный — совпадение, красный — пропущенные слова, оранжевый — лишние)\n")
        append("  5. На вкладке «Разбивка» можно редактировать отдельные сегменты, менять голос, разбивать на части\n")
        append("  6. На панели справа настройте голос для каждого персонажа\n")
        append("  7. Нажмите «Озвучить» для генерации аудио\n\n")

        // ── Опции ──
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("3. Опции и возможности\n")
        }

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Главы") }
        append(" — разбейте текст на главы (кнопки +, переименовать, удалить в заголовке)\n")

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Книги") }
        append(" — «Сохранить книгу» / «Загрузить книгу» сохраняет все главы и настройки голосов\n")

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Настройки голоса") }
        append(" — для каждого персонажа: голос (18 вариантов), роль (neutral, good, evil, friendly, strict, whisper), скорость (0.1–3.0), высота тона\n")

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Повторить ошибки") }
        append(" — повторная озвучка только неудавшихся сегментов (остальные берутся из кэша)\n")

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Объединение голосов") }
        append(" — объединяет два голоса в один (если AI создал лишних)\n")

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Очистить кэш") }
        append(" — удалить кэшированные аудио-сегменты и пересоздать с нуля\n")

        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Сбросить разметку") }
        append(" — вернуть исходный текст без голосовых тегов\n\n")

        // ── Документация ──
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("4. Документация Yandex Cloud\n")
        }
        append("  - ")
        pushStringAnnotation("url", links["link_speechkit"]!!)
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Yandex SpeechKit — общая документация")
        }
        pop()
        append("\n  - ")
        pushStringAnnotation("url", links["link_api_key"]!!)
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Создание API-ключа")
        }
        pop()
        append("\n  - ")
        pushStringAnnotation("url", links["link_folder_id"]!!)
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Получение идентификатора каталога (Folder ID)")
        }
        pop()
        append("\n  - ")
        pushStringAnnotation("url", links["link_console"]!!)
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Yandex Cloud Console")
        }
        pop()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.widthIn(max = 700.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Помощь",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ClickableText(
                    text = annotatedText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    onClick = { offset ->
                        annotatedText.getStringAnnotations("url", offset, offset)
                            .firstOrNull()?.let { annotation ->
                                try {
                                    Desktop.getDesktop().browse(URI(annotation.item))
                                } catch (_: Exception) {
                                    // ignore if browser can't open
                                }
                            }
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Закрыть")
                }
            }
        }
    }
}
