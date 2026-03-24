package by.tigre.speechhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import by.tigre.speechhelper.domain.ChapterInfo
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
import by.tigre.speechhelper.data.LlmModelsApi
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.domain.LlmConfig
import java.awt.Desktop
import java.net.URI
import by.tigre.speechhelper.domain.LlmProvider
import kotlinx.coroutines.launch

private data class LlmPreset(val label: String, val baseUrl: String, val model: String)

private val LLM_PRESETS = listOf(
    LlmPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o"),
    LlmPreset("Ollama", "http://localhost:11434/v1", "llama3.2"),
    LlmPreset("LM Studio", "http://localhost:1234/v1", ""),
    LlmPreset("Yandex AI", "https://ai.api.cloud.yandex.net/v1", ""),
)

@Composable
fun TokenDialog(
    onDismiss: () -> Unit,
    onSave: (token: String) -> Unit,
    onOpenHelp: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(TokenStorage.iamToken) }
    var folderId by remember { mutableStateOf(TokenStorage.folderId) }

    val savedLlm = remember { TokenStorage.llmConfig }
    var llmProvider by remember { mutableStateOf(savedLlm.provider) }
    var llmBaseUrl by remember { mutableStateOf(savedLlm.baseUrl.ifBlank { savedLlm.provider.defaultBaseUrl }) }
    var llmApiKey by remember { mutableStateOf(savedLlm.apiKey) }
    var llmModel by remember { mutableStateOf(savedLlm.model) }
    val linkColor = Color(0xFF1976D2)

    val availableModels = remember { mutableStateListOf<String>() }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    fun onProviderSelected(provider: LlmProvider) {
        llmProvider = provider
        llmBaseUrl = provider.defaultBaseUrl
        if (provider != savedLlm.provider) {
            llmModel = ""
        }
        availableModels.clear()
        modelsError = ""
    }

    fun connectAndFetchModels() {
        scope.launch {
            isLoadingModels = true
            modelsError = ""
            availableModels.clear()
            try {
                val config = LlmConfig(
                    provider = llmProvider,
                    baseUrl = llmBaseUrl.trim(),
                    apiKey = llmApiKey.trim(),
                    model = "",
                )
                val models = LlmModelsApi.fetchModels(config, folderId)
                availableModels.addAll(models)
                if (models.isEmpty()) modelsError = "Моделей не найдено"
            } catch (e: Exception) {
                modelsError = "Ошибка: ${e.message}"
            } finally {
                isLoadingModels = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Yandex SpeechKit ──────────────────────────────────────
                Text("Yandex SpeechKit", style = MaterialTheme.typography.titleSmall)
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ── LLM ───────────────────────────────────────────────────
                Text("LLM для авто-разметки", style = MaterialTheme.typography.titleSmall)

                // Provider selector
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LlmProvider.entries.forEach { provider ->
                        val selected = provider == llmProvider
                        if (selected) {
                            Button(onClick = {}) {
                                Text(provider.label, style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            OutlinedButton(onClick = { onProviderSelected(provider) }) {
                                Text(provider.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Provider-specific fields
                when (llmProvider) {
                    LlmProvider.OpenAI -> {
                        OutlinedTextField(
                            value = llmBaseUrl,
                            onValueChange = { llmBaseUrl = it },
                            label = { Text("URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = llmApiKey,
                            onValueChange = { llmApiKey = it },
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                    LlmProvider.Ollama, LlmProvider.LMStudio -> {
                        OutlinedTextField(
                            value = llmBaseUrl,
                            onValueChange = { llmBaseUrl = it },
                            label = { Text("URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = llmApiKey,
                            onValueChange = { llmApiKey = it },
                            label = { Text("API Token (опционально)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    LlmProvider.YandexCloud -> {
                        Text(
                            "Folder ID: ${folderId.ifBlank { "(не задан выше)" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Connect button + model dropdown
                val canConnect = when (llmProvider) {
                    LlmProvider.OpenAI -> llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank()
                    LlmProvider.Ollama, LlmProvider.LMStudio -> llmBaseUrl.isNotBlank()
                    LlmProvider.YandexCloud -> folderId.isNotBlank()
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { connectAndFetchModels() },
                        enabled = canConnect && !isLoadingModels,
                    ) {
                        Text("Подключить")
                    }

                    if (isLoadingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }

                    if (availableModels.isNotEmpty()) {
                        Box {
                            OutlinedButton(onClick = { modelDropdownExpanded = true }) {
                                Text(llmModel.ifBlank { "Выбрать модель" }, maxLines = 1)
                            }
                            DropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false },
                            ) {
                                availableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            llmModel = model
                                            modelDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (modelsError.isNotBlank()) {
                    Text(modelsError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (llmModel.isNotBlank()) {
                    Text(
                        "Модель: $llmModel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    TokenStorage.folderId = folderId
                    TokenStorage.llmConfig = LlmConfig(
                        provider = llmProvider,
                        baseUrl = llmBaseUrl.trim(),
                        apiKey = llmApiKey.trim(),
                        model = llmModel.trim(),
                    )
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
fun ChaptersWorkflowDialog(
    chapters: List<ChapterInfo>,
    currentChapterId: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onLaunchBatchMarkup: (List<String>) -> Unit,
    onSetMarkupDone: (String, Boolean) -> Unit,
    onSetVoiceDone: (String, Boolean) -> Unit,
    audioExists: (String) -> Boolean,
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пакетная обработка: разметка и озвучка") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                Text(
                    "Отметьте галочками главы для пакетной авто-разметки. " +
                        "Чипы «Разметка» и «Озвучка» — ваши ручные отметки прогресса.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { selectedIds = chapters.map { it.id }.toSet() },
                    ) {
                        Text("Выбрать все")
                    }
                    TextButton(onClick = { selectedIds = emptySet() }) {
                        Text("Снять выбор")
                    }
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    items(chapters, key = { it.id }) { ch ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = ch.id in selectedIds,
                                onCheckedChange = {
                                    selectedIds =
                                        if (ch.id in selectedIds) selectedIds - ch.id else selectedIds + ch.id
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ch.name,
                                    fontWeight = if (ch.id == currentChapterId) FontWeight.Bold else null,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = ch.markupDone,
                                        onClick = { onSetMarkupDone(ch.id, !ch.markupDone) },
                                        label = { Text("Разметка") },
                                    )
                                    FilterChip(
                                        selected = ch.voiceDone,
                                        onClick = {
                                            if (audioExists(ch.id)) {
                                                onSetVoiceDone(ch.id, !ch.voiceDone)
                                            }
                                        },
                                        enabled = audioExists(ch.id),
                                        label = { Text("Озвучка") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onLaunchBatchMarkup(selectedIds.toList()) },
                enabled = selectedIds.isNotEmpty() && !isLoading,
            ) {
                Text("Авто-разметка выбранных")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
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
