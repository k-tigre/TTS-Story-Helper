package by.tigre.speechhelper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class VoiceInfo(val id: String, val gender: String, val roles: List<String>)

data class VoiceSettings(
    val voice: String = "dasha",
    val role: String = "",
    val speed: Double = 1.0,
    val pitchShift: Double = 0.0,
)

private val API_VOICES_INFO = listOf(
    // Russian (ru-RU)
    VoiceInfo("alena", "Ж", listOf("neutral", "good")),
    VoiceInfo("filipp", "М", emptyList()),
    VoiceInfo("ermil", "М", listOf("neutral", "good")),
    VoiceInfo("jane", "Ж", listOf("neutral", "good", "evil")),
    VoiceInfo("omazh", "Ж", listOf("neutral", "evil")),
    VoiceInfo("zahar", "М", listOf("neutral", "good")),
    VoiceInfo("dasha", "Ж", listOf("neutral", "good", "friendly")),
    VoiceInfo("julia", "Ж", listOf("neutral", "strict")),
    VoiceInfo("lera", "Ж", listOf("neutral", "friendly")),
    VoiceInfo("masha", "Ж", listOf("good", "strict", "friendly")),
    VoiceInfo("marina", "Ж", listOf("neutral", "whisper", "friendly")),
    VoiceInfo("alexander", "М", listOf("neutral", "good")),
    VoiceInfo("kirill", "М", listOf("neutral", "strict", "good")),
    VoiceInfo("anton", "М", listOf("neutral", "good")),
    VoiceInfo("madi_ru", "М", emptyList()),
    VoiceInfo("saule_ru", "Ж", listOf("neutral", "strict", "whisper")),
    VoiceInfo("zamira_ru", "Ж", listOf("neutral", "strict", "friendly")),
    VoiceInfo("zhanar_ru", "Ж", listOf("neutral", "strict", "friendly")),
    VoiceInfo("yulduz_ru", "Ж", listOf("neutral", "strict", "friendly", "whisper")),
)

private val API_VOICES = API_VOICES_INFO.map { it.id }

private val FORMATS = listOf("mp3", "ogg", "wav")

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun App() {
    var showTokenDialog by remember { mutableStateOf(!TokenStorage.hasCredentials()) }

    MaterialTheme {
        if (showTokenDialog) {
            TokenDialog(
                onDismiss = {
                    if (TokenStorage.hasCredentials()) showTokenDialog = false
                },
                onSave = { token ->
                    TokenStorage.iamToken = token
                    showTokenDialog = false
                }
            )
        }

        MainScreen(onTokenRefresh = { showTokenDialog = true })
    }
}

@Composable
private fun TokenDialog(
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
private fun CreateChapterDialog(
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
            Button(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun RenameChapterDialog(
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
            Button(
                onClick = { onRename(name) },
                enabled = name.isNotBlank(),
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
private fun DeleteChapterDialog(
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun SaveBookDialog(
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
            Button(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
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
private fun LoadBookDialog(
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
                                Text(
                                    text = bookName,
                                    modifier = Modifier.fillMaxWidth(),
                                )
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun ProgressDialog(progressMessage: String) {
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
private fun SplitSegmentDialog(
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
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    minLines = 5,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parts = editedText.split("===").map { it.trim() }.filter { it.isNotBlank() }
                    if (parts.size > 1) {
                        onSplit(parts)
                    }
                },
                enabled = partsCount > 1,
            ) {
                Text("Разбить ($partsCount)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSelector(
    chapters: List<ChapterInfo>,
    currentChapterId: String,
    onSelectChapter: (String) -> Unit,
    onCreateChapter: () -> Unit,
    onRenameChapter: () -> Unit,
    onDeleteChapter: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = chapters.find { it.id == currentChapterId }?.name ?: "—"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(currentName, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                chapters.forEach { chapter ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = chapter.name,
                                fontWeight = if (chapter.id == currentChapterId)
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                else
                                    null,
                            )
                        },
                        onClick = {
                            onSelectChapter(chapter.id)
                            expanded = false
                        },
                    )
                }
            }
        }

        IconButton(onClick = onCreateChapter) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }

        IconButton(onClick = onRenameChapter) {
            Text("\u270E", style = MaterialTheme.typography.bodyMedium)
        }

        if (chapters.size > 1) {
            IconButton(onClick = onDeleteChapter) {
                Text(
                    "\u2716", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun MainScreen(onTokenRefresh: () -> Unit) {
    val scope = rememberCoroutineScope()

    // Chapter management state
    var chapters by remember { mutableStateOf(SessionStorage.listChapters()) }
    var currentChapterId by remember { mutableStateOf(SessionStorage.ensureCurrentChapter()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showSaveBookDialog by remember { mutableStateOf(false) }
    var showLoadBookDialog by remember { mutableStateOf(false) }
    var showFolderIdDialog by remember { mutableStateOf(false) }
    var currentBookName by remember { mutableStateOf(SessionStorage.currentBookName) }

    // Chapter content state
    var text by remember(currentChapterId) { mutableStateOf(SessionStorage.getChapterText(currentChapterId)) }
    var selectedVoice by remember { mutableStateOf(API_VOICES[0]) }
    var selectedFormat by remember { mutableStateOf(FORMATS[0]) }
    var speed by remember { mutableStateOf(1.0) }
    var pitchShift by remember { mutableStateOf(0.0) }
    var selectedRole by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    // Chapter audio player
    var chapterAudioPath by remember<MutableState<String?>>(currentChapterId) {
        mutableStateOf(SessionStorage.getChapterAudioPath(currentChapterId))
    }
    val chapterPlayer = remember<ChapterAudioPlayer> { ChapterAudioPlayer() }
    var playerIsPlaying by remember { mutableStateOf(false) }
    var playerPositionMs by remember { mutableStateOf(0L) }
    var playerDurationMs by remember { mutableStateOf(0L) }
    var playerReady by remember { mutableStateOf(false) }

    // Voice mapping is global (shared across all chapters)
    val voiceMapping = remember { mutableStateMapOf<String, VoiceSettings>() }

    // Load saved mapping once
    LaunchedEffect(Unit) {
        val saved = SessionStorage.voiceMapping
        voiceMapping.putAll(saved)
    }

    // Save text on change (debounced)
    LaunchedEffect(currentChapterId) {
        snapshotFlow { text }
            .debounce(30_000)
            .collect { SessionStorage.setChapterText(currentChapterId, it) }
    }

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
        statusMessage = ""
    }

    val hasMarkers = TextParser.hasVoiceMarkers(text)
    val detectedVoices: Set<String> = remember(text) {
        if (hasMarkers) TextParser.extractVoiceNames(text) else emptySet()
    }

    // Ensure all detected voices have a mapping
    LaunchedEffect(detectedVoices) {
        for (name in detectedVoices) {
            if (name !in voiceMapping) {
                voiceMapping[name] = VoiceSettings()
            }
        }
    }

    fun saveVoiceMapping() {
        SessionStorage.voiceMapping = voiceMapping.toMap()
    }

    fun removeUnusedVoices() {
        val unused = voiceMapping.keys - detectedVoices
        for (name in unused) {
            voiceMapping.remove(name)
        }
        saveVoiceMapping()
        statusMessage = if (unused.isEmpty()) "Нет неиспользуемых голосов" else "Удалено голосов: ${unused.size}"
    }

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
                            text = result.text
                            saveCurrentChapter()
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

    fun launchMultiVoiceSynthesis(retryVoice: String? = null) {
        val segments = TextParser.parse(text)
        if (segments.isEmpty()) {
            statusMessage = "Ошибка: не найдены сегменты текста"
            return
        }
        saveVoiceMapping()
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = ""
            try {
                val cacheDir = withContext(Dispatchers.IO) {
                    SessionStorage.getChapterCacheDir(currentChapterId)
                }
                val errors = mutableListOf<String>()

                for ((index, segment) in segments.withIndex()) {
                    val settings = if (segment.voiceName != null) {
                        voiceMapping[segment.voiceName] ?: VoiceSettings()
                    } else {
                        VoiceSettings()
                    }
                    val partFile = File(cacheDir, "part_%03d.mp3".format(index))

                    // Skip already cached unless retrying this voice
                    if (partFile.exists() && (retryVoice == null || segment.voiceName != retryVoice)) {
                        progressMessage = "Озвучивание ${index + 1} из ${segments.size} — кэш"
                        continue
                    }

                    try {
                        SpeechKitApi.synthesize(
                            text = segment.text,
                            voice = settings.voice,
                            role = settings.role.ifBlank { null },
                            speed = settings.speed,
                            pitchShift = settings.pitchShift,
                            format = "mp3",
                            token = TokenStorage.iamToken,
                        ).collectLatest { result ->
                            when (result) {
                                is SynthesisResult.InProgress ->
                                    progressMessage = "Озвучивание ${index + 1} из ${segments.size}\nголос: ${segment.voiceName ?: "по умолчанию"} → ${settings.voice}\n${result.message}"
                                is SynthesisResult.Done ->
                                    withContext(Dispatchers.IO) { partFile.writeBytes(result.bytes) }
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("#${index + 1} ${segment.voiceName ?: "по умолчанию"}: ${e.message}")
                        // Delete failed part so it will be retried next time
                        partFile.delete()
                    }
                }

                // Assemble all available parts in order
                progressMessage = "Склейка аудио\n${segments.size} сегментов"
                val allParts = (0 until segments.size).mapNotNull { i ->
                    val f = File(cacheDir, "part_%03d.mp3".format(i))
                    if (f.exists()) f.readBytes() else null
                }

                if (allParts.isEmpty()) {
                    statusMessage = "Ошибка: ни один сегмент не озвучен"
                } else {
                    val combined = allParts.reduce { acc, bytes -> acc + bytes }
                    val chapterName = chapters.find { it.id == currentChapterId }?.name ?: ""
                    val filePath = saveAudioFile(combined, "mp3", bookName = currentBookName, chapterName = chapterName)
                    SessionStorage.setChapterAudioPath(currentChapterId, filePath)
                    chapterAudioPath = filePath
                    val successCount = allParts.size
                    val totalCount = segments.size
                    statusMessage = if (errors.isEmpty()) {
                        "Сохранено ($totalCount сегментов): $filePath"
                    } else {
                        "Сохранено ($successCount/$totalCount): $filePath\nОшибки:\n${errors.joinToString("\n")}"
                    }
                }
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    fun clearCache() {
        SessionStorage.clearChapterCache(currentChapterId)
        statusMessage = "Кэш очищен"
    }

    fun launchSimpleSynthesis() {
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = "Синтез речи\nголос: $selectedVoice"
            try {
                SpeechKitApi.synthesize(
                    text = text,
                    voice = selectedVoice,
                    role = selectedRole.ifBlank { null },
                    speed = speed,
                    pitchShift = pitchShift,
                    format = selectedFormat,
                    token = TokenStorage.iamToken,
                ).collectLatest { result ->
                    when (result) {
                        is SynthesisResult.InProgress ->
                            progressMessage = "Синтез речи\nголос: $selectedVoice\n${result.message}"
                        is SynthesisResult.Done -> {
                            val chapterName = chapters.find { it.id == currentChapterId }?.name ?: ""
                            val filePath =
                                saveAudioFile(result.bytes, selectedFormat, bookName = currentBookName, chapterName = chapterName)
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

    // Load audio when chapter changes or audio path changes
    LaunchedEffect(currentChapterId, chapterAudioPath) {
        playerIsPlaying = false
        playerPositionMs = 0L
        playerDurationMs = 0L
        playerReady = false
        chapterPlayer.close()
        val path = chapterAudioPath
        if (path != null && File(path).exists()) {
            try {
                chapterPlayer.open(path) {
                    playerIsPlaying = false
                    playerPositionMs = 0L
                }
                playerDurationMs = chapterPlayer.durationMs
                playerReady = true
            } catch (_: Exception) {
                playerReady = false
            }
        }
    }

    // Update position while playing
    LaunchedEffect(playerIsPlaying) {
        if (playerIsPlaying) {
            while (isActive && chapterPlayer.isPlaying) {
                playerPositionMs = chapterPlayer.currentPositionMs
                delay(200)
            }
            playerIsPlaying = chapterPlayer.isPlaying
        }
    }

    // View mode toggle: 0 = Text, 1 = Segments
    var viewMode by remember { mutableStateOf(0) }

    // Mutable segments for the breakdown view
    val segments = remember { mutableStateListOf<TextSegment>() }

    // Sync segments from text when switching to segments view or changing chapter
    LaunchedEffect(viewMode, currentChapterId) {
        if (viewMode == 1) {
            segments.clear()
            segments.addAll(TextParser.parse(text))
        }
    }

    // Sync text back when switching to text view
    fun syncTextFromSegments() {
        text = TextParser.buildText(segments.toList())
    }

    // Dialogs
    if (showCreateDialog) {
        CreateChapterDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                saveCurrentChapter()
                val id = SessionStorage.createChapter(name)
                chapters = SessionStorage.listChapters()
                switchToChapter(id)
                showCreateDialog = false
            },
        )
    }

    if (showRenameDialog) {
        val currentName = chapters.find { it.id == currentChapterId }?.name ?: ""
        RenameChapterDialog(
            currentName = currentName,
            onDismiss = { showRenameDialog = false },
            onRename = { name ->
                SessionStorage.renameChapter(currentChapterId, name)
                chapters = SessionStorage.listChapters()
                showRenameDialog = false
            },
        )
    }

    if (showDeleteDialog) {
        val currentName = chapters.find { it.id == currentChapterId }?.name ?: ""
        DeleteChapterDialog(
            chapterName = currentName,
            onDismiss = { showDeleteDialog = false },
            onDelete = {
                val idToDelete = currentChapterId
                SessionStorage.deleteChapter(idToDelete)
                chapters = SessionStorage.listChapters()
                currentChapterId = SessionStorage.ensureCurrentChapter()
                showDeleteDialog = false
            },
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Очистить всё?") },
            text = { Text("Все главы, настройки голосов и кэш будут удалены. Это действие нельзя отменить.") },
            confirmButton = {
                Button(
                    onClick = {
                        saveCurrentChapter()
                        SessionStorage.clearAllData()
                        val id = SessionStorage.ensureCurrentChapter()
                        chapters = SessionStorage.listChapters()
                        currentChapterId = id
                        voiceMapping.clear()
                        currentBookName = ""
                        statusMessage = "Всё очищено"
                        showClearAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (showSaveBookDialog) {
        SaveBookDialog(
            onDismiss = { showSaveBookDialog = false },
            onSave = { bookName ->
                saveCurrentChapter()
                saveVoiceMapping()
                SessionStorage.saveBook(bookName)
                currentBookName = bookName
                SessionStorage.currentBookName = bookName
                statusMessage = "Книга \"$bookName\" сохранена"
                showSaveBookDialog = false
            },
        )
    }

    if (showFolderIdDialog) {
        var folderIdInput by remember { mutableStateOf(TokenStorage.folderId) }
        AlertDialog(
            onDismissRequest = { showFolderIdDialog = false },
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
                    onClick = {
                        TokenStorage.folderId = folderIdInput
                        showFolderIdDialog = false
                        launchAutoMarkup()
                    },
                    enabled = folderIdInput.isNotBlank(),
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderIdDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (showLoadBookDialog) {
        LoadBookDialog(
            onDismiss = { showLoadBookDialog = false },
            onLoad = { bookName ->
                saveCurrentChapter()
                if (SessionStorage.loadBook(bookName)) {
                    chapters = SessionStorage.listChapters()
                    currentChapterId = SessionStorage.ensureCurrentChapter()
                    voiceMapping.clear()
                    voiceMapping.putAll(SessionStorage.voiceMapping)
                    currentBookName = bookName
                    SessionStorage.currentBookName = bookName
                    statusMessage = "Книга \"$bookName\" загружена"
                } else {
                    statusMessage = "Ошибка: не удалось загрузить книгу"
                }
                showLoadBookDialog = false
            },
            onDelete = { bookName ->
                SessionStorage.deleteBook(bookName)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ChapterSelector(
                        chapters = chapters,
                        currentChapterId = currentChapterId,
                        onSelectChapter = { switchToChapter(it) },
                        onCreateChapter = { showCreateDialog = true },
                        onRenameChapter = { showRenameDialog = true },
                        onDeleteChapter = { showDeleteDialog = true },
                    )
                },
                actions = {
                    TextButton(onClick = {
                        if (currentBookName.isNotBlank()) {
                            saveCurrentChapter()
                            saveVoiceMapping()
                            SessionStorage.saveBook(currentBookName)
                            statusMessage = "Книга \"$currentBookName\" сохранена"
                        } else {
                            showSaveBookDialog = true
                        }
                    }) {
                        Text("Сохранить книгу")
                    }
                    TextButton(onClick = { showLoadBookDialog = true }) {
                        Text("Загрузить книгу")
                    }
                    TextButton(
                        onClick = { showClearAllDialog = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Очистить всё")
                    }
                    IconButton(onClick = onTokenRefresh) {
                        Text("\u2699", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // View mode toggle
            if (hasMarkers) {
                PrimaryTabRow(
                    selectedTabIndex = viewMode,
                    modifier = Modifier.width(300.dp),
                ) {
                    Tab(selected = viewMode == 0, onClick = {
                        if (viewMode == 1) syncTextFromSegments()
                        viewMode = 0
                    }) {
                        Text("Текст", modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Tab(selected = viewMode == 1, onClick = { viewMode = 1 }) {
                        Text("Разбивка", modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            // Main area
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (viewMode == 0 || !hasMarkers) {
                    // Text view
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Текст для озвучивания") },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        minLines = 5,
                    )
                } else {
                    // Segments breakdown view
                    SegmentsView(
                        segments = segments,
                        voiceMapping = voiceMapping,
                        onSegmentTextChange = { index, newText ->
                            segments[index] = segments[index].copy(text = newText)
                        },
                        onSplitSegment = { index, parts ->
                            val voiceName = segments[index].voiceName
                            segments.removeAt(index)
                            segments.addAll(index, parts.map { TextSegment(voiceName = voiceName, text = it) })
                        },
                        onRemarkupSegment = { index ->
                            val folderId = TokenStorage.folderId
                            if (folderId.isBlank()) {
                                showFolderIdDialog = true
                            } else if (!isLoading) {
                                isLoading = true
                                scope.launch {
                                    try {
                                        val segmentText = segments[index].text
                                        AiMarkupApi.fixDialog(
                                            text = segmentText,
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
                                                            if (name !in voiceMapping) {
                                                                voiceMapping[name] = VoiceSettings()
                                                            }
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
                        },
                        onChangeSegmentVoice = { index, newVoiceName ->
                            segments[index] = segments[index].copy(voiceName = newVoiceName)
                        },
                        availableVoiceNames = voiceMapping.keys.toList().sorted(),
                        isLoading = isLoading,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Voice mapping panel — visible when any voices are configured
                if (voiceMapping.isNotEmpty()) {
                    val allVoiceNames = (detectedVoices + voiceMapping.keys).toList()
                        .sortedWith(compareByDescending<String> { it in detectedVoices }.thenBy { it })
                    VoiceMappingPanel(
                        voiceNames = allVoiceNames,
                        activeVoiceNames = detectedVoices,
                        mapping = voiceMapping,
                        onSettingsChange = { name, settings ->
                            voiceMapping[name] = settings
                            saveVoiceMapping()
                        },
                        onRetryVoice = { name ->
                            launchMultiVoiceSynthesis(retryVoice = name)
                        },
                        onMergeVoice = { fromName, toName ->
                            // Rename in raw text
                            text = text
                                .replace("[$fromName]", "[$toName]")
                                .replace("[/$fromName]", "[/$toName]")
                            saveCurrentChapter()
                            // Update segments if in segment view
                            if (viewMode == 1) {
                                for (i in segments.indices) {
                                    if (segments[i].voiceName == fromName) {
                                        segments[i] = segments[i].copy(voiceName = toName)
                                    }
                                }
                            }
                            // Remove old voice mapping
                            voiceMapping.remove(fromName)
                            // Ensure target voice has mapping
                            if (toName !in voiceMapping) {
                                voiceMapping[toName] = VoiceSettings()
                            }
                            saveVoiceMapping()
                            statusMessage = "Голос \"$fromName\" объединён с \"$toName\""
                        },
                        onAddVoice = { name ->
                            voiceMapping[name] = VoiceSettings()
                            saveVoiceMapping()
                        },
                        onCleanupVoices = { removeUnusedVoices() },
                        isLoading = isLoading,
                        modifier = Modifier.width(350.dp).fillMaxHeight(),
                    )
                }
            }

            // Controls row
            if (!hasMarkers) {
                val currentVoiceInfo = API_VOICES_INFO.find { it.id == selectedVoice }
                val availableRoles = currentVoiceInfo?.roles ?: emptyList()

                // Reset role if not available for this voice
                LaunchedEffect(selectedVoice) {
                    if (selectedRole.isNotBlank() && selectedRole !in availableRoles) {
                        selectedRole = ""
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DropdownSelector("Голос", API_VOICES, selectedVoice) { selectedVoice = it }
                    DropdownSelector("Формат", FORMATS, selectedFormat) { selectedFormat = it }
                    if (availableRoles.isNotEmpty()) {
                        DropdownSelector(
                            "Амплуа",
                            listOf("") + availableRoles,
                            selectedRole,
                            displayTransform = { it.ifBlank { "нет" } }
                        ) { selectedRole = it }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Скорость: ${"%.1f".format(speed)}")
                    Slider(
                        value = speed.toFloat(),
                        onValueChange = { speed = it.toDouble() },
                        valueRange = 0.1f..3.0f,
                        modifier = Modifier.width(200.dp),
                    )

                    Text("Тон: ${"%.0f".format(pitchShift)}")
                    Slider(
                        value = pitchShift.toFloat(),
                        onValueChange = { pitchShift = it.toDouble() },
                        valueRange = -1000f..1000f,
                        modifier = Modifier.width(200.dp),
                    )
                }
            }

            // Action row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (viewMode == 1) syncTextFromSegments()
                        if (hasMarkers) {
                            clearCache()
                            launchMultiVoiceSynthesis()
                        } else {
                            launchSimpleSynthesis()
                        }
                    },
                    enabled = text.isNotBlank() && !isLoading && TokenStorage.hasCredentials(),
                ) {
                    Text("Озвучить")
                }

                OutlinedButton(
                    onClick = { launchAutoMarkup() },
                    enabled = text.isNotBlank() && !isLoading && TokenStorage.hasCredentials() && !hasMarkers,
                ) {
                    Text("Авто-разметка")
                }

                if (hasMarkers) {
                    OutlinedButton(
                        onClick = {
                            if (viewMode == 1) syncTextFromSegments()
                            launchMultiVoiceSynthesis()
                        },
                        enabled = text.isNotBlank() && !isLoading && TokenStorage.hasCredentials(),
                    ) {
                        Text("Повторить ошибки")
                    }

                    TextButton(
                        onClick = { clearCache() },
                        enabled = !isLoading,
                    ) {
                        Text("Очистить кэш")
                    }
                }

            }

            // Chapter audio player
            ChapterPlayerBar(
                isReady = playerReady,
                isPlaying = playerIsPlaying,
                positionMs = playerPositionMs,
                durationMs = playerDurationMs,
                onPlayPause = {
                    if (playerIsPlaying) {
                        chapterPlayer.pause()
                        playerIsPlaying = false
                    } else {
                        chapterPlayer.play()
                        playerIsPlaying = true
                    }
                },
                onSeek = { posMs ->
                    chapterPlayer.seekTo(posMs)
                    playerPositionMs = posMs
                },
            )

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    color = if (statusMessage.startsWith("Ошибка"))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (isLoading) {
            ProgressDialog(progressMessage = progressMessage)
        }
    }
}

@Composable
private fun SegmentsView(
    segments: List<TextSegment>,
    voiceMapping: Map<String, VoiceSettings>,
    onSegmentTextChange: (index: Int, newText: String) -> Unit,
    onRemarkupSegment: (index: Int) -> Unit,
    onSplitSegment: (index: Int, parts: List<String>) -> Unit,
    onChangeSegmentVoice: (index: Int, newVoiceName: String?) -> Unit,
    availableVoiceNames: List<String>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var playingIndex by remember { mutableStateOf(-1) }
    var playError by remember { mutableStateOf<String?>(null) }
    var splitDialogIndex by remember { mutableStateOf(-1) }

    if (splitDialogIndex >= 0 && splitDialogIndex < segments.size) {
        val seg = segments[splitDialogIndex]
        SplitSegmentDialog(
            segmentText = seg.text,
            voiceName = seg.voiceName,
            onDismiss = { splitDialogIndex = -1 },
            onSplit = { parts ->
                onSplitSegment(splitDialogIndex, parts)
                splitDialogIndex = -1
            },
        )
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(segments) { index, segment ->
            val settings = if (segment.voiceName != null) {
                voiceMapping[segment.voiceName] ?: VoiceSettings()
            } else {
                VoiceSettings()
            }
            val voiceInfo = API_VOICES_INFO.find { it.id == settings.voice }
            val isPlaying = playingIndex == index

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Header: number + voice name + mapped voice + play button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Segment number
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }

                        // Voice label (clickable to change)
                        Box {
                            var voiceDropdownExpanded by remember { mutableStateOf(false) }
                            Text(
                                text = segment.voiceName ?: "без голоса",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (segment.voiceName != null)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { voiceDropdownExpanded = true },
                            )
                            DropdownMenu(
                                expanded = voiceDropdownExpanded,
                                onDismissRequest = { voiceDropdownExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "без голоса",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = {
                                        onChangeSegmentVoice(index, null)
                                        voiceDropdownExpanded = false
                                    },
                                )
                                availableVoiceNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = name,
                                                fontWeight = if (name == segment.voiceName)
                                                    androidx.compose.ui.text.font.FontWeight.Bold
                                                else null,
                                            )
                                        },
                                        onClick = {
                                            onChangeSegmentVoice(index, name)
                                            voiceDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Mapped voice info
                        Text(
                            text = "\u2192 ${settings.voice} (${voiceInfo?.gender ?: "?"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )

                        // Split button
                        IconButton(
                            onClick = { splitDialogIndex = index },
                            enabled = !isLoading && playingIndex == -1,
                        ) {
                            Text(
                                "\u2702",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }

                        // Remarkup button
                        IconButton(
                            onClick = { onRemarkupSegment(index) },
                            enabled = !isLoading && playingIndex == -1,
                        ) {
                            Text(
                                "\u2728",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }

                        // Play button
                        if (isPlaying) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            IconButton(
                                onClick = {
                                    playError = null
                                    playingIndex = index
                                    scope.launch {
                                        try {
                                            SpeechKitApi.synthesize(
                                                text = segment.text,
                                                voice = settings.voice,
                                                role = settings.role.ifBlank { null },
                                                speed = settings.speed,
                                                pitchShift = settings.pitchShift,
                                                format = "wav",
                                                token = TokenStorage.iamToken,
                                            ).collectLatest { result ->
                                                when (result) {
                                                    is SynthesisResult.InProgress -> {}
                                                    is SynthesisResult.Done -> AudioPlayer.play(result.bytes)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            playError = "#${index + 1}: ${e.message}"
                                        } finally {
                                            playingIndex = -1
                                        }
                                    }
                                },
                                enabled = !isLoading && playingIndex == -1,
                            ) {
                                Text(
                                    "\u25B6",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    // Editable text
                    OutlinedTextField(
                        value = segment.text,
                        onValueChange = { onSegmentTextChange(index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (playError != null) {
            item {
                Text(
                    text = playError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun VoiceMappingPanel(
    voiceNames: List<String>,
    activeVoiceNames: Set<String>,
    mapping: Map<String, VoiceSettings>,
    onSettingsChange: (name: String, settings: VoiceSettings) -> Unit,
    onRetryVoice: (name: String) -> Unit,
    onMergeVoice: (fromName: String, toName: String) -> Unit,
    onAddVoice: (name: String) -> Unit,
    onCleanupVoices: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val expandedVoices = remember { mutableStateMapOf<String, Boolean>() }
    var showAddVoiceDialog by remember { mutableStateOf(false) }

    if (showAddVoiceDialog) {
        var newVoiceName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddVoiceDialog = false },
            title = { Text("Добавить голос") },
            text = {
                OutlinedTextField(
                    value = newVoiceName,
                    onValueChange = { newVoiceName = it },
                    label = { Text("Имя голоса") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddVoice(newVoiceName.trim())
                        showAddVoiceDialog = false
                    },
                    enabled = newVoiceName.trim().isNotBlank() && newVoiceName.trim() !in voiceNames,
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddVoiceDialog = false }) { Text("Отмена") }
            },
        )
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Голоса", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { showAddVoiceDialog = true }) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
                val hasUnused = voiceNames.any { it !in activeVoiceNames }
                if (hasUnused) {
                    TextButton(
                        onClick = onCleanupVoices,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("Убрать неиспользуемые", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider()

            voiceNames.forEach { name ->
                val isActive = name in activeVoiceNames
                val settings = mapping[name] ?: VoiceSettings()
                val expanded = expandedVoices[name] ?: false
                val voiceInfo = API_VOICES_INFO.find { it.id == settings.voice }
                val availableRoles = voiceInfo?.roles ?: emptyList()

                // Compact summary (always visible, clickable to toggle)
                val roleSummary = settings.role.ifBlank { "-" }
                val speedSummary = "%.1f".format(settings.speed)
                val pitchSummary = "%.0f".format(settings.pitchShift)
                val genderIcon = voiceInfo?.gender ?: "?"

                val contentAlpha = if (isActive) 1f else 0.45f

                Column {
                    // Collapsed header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedVoices[name] = !expanded }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = if (expanded) "\u25BC" else "\u25B6",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                        Text(
                            text = "${settings.voice}($genderIcon) | $roleSummary | x$speedSummary | ${pitchSummary}Hz",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        )
                    }

                    // Expanded editor
                    AnimatedVisibility(visible = expanded) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Голос:", style = MaterialTheme.typography.bodySmall)
                                DropdownSelector(
                                    label = "",
                                    items = API_VOICES,
                                    selected = settings.voice,
                                    onSelect = { voice ->
                                        val newRoles = API_VOICES_INFO.find { it.id == voice }?.roles ?: emptyList()
                                        val newRole = if (settings.role in newRoles) settings.role else ""
                                        onSettingsChange(name, settings.copy(voice = voice, role = newRole))
                                    }
                                )
                            }

                            if (availableRoles.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Амплуа:", style = MaterialTheme.typography.bodySmall)
                                    DropdownSelector(
                                        label = "",
                                        items = listOf("") + availableRoles,
                                        selected = settings.role,
                                        displayTransform = { it.ifBlank { "нет" } },
                                        onSelect = { onSettingsChange(name, settings.copy(role = it)) }
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Скорость: $speedSummary", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = settings.speed.toFloat(),
                                    onValueChange = { onSettingsChange(name, settings.copy(speed = it.toDouble())) },
                                    valueRange = 0.1f..3.0f,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Тон: $pitchSummary", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = settings.pitchShift.toFloat(),
                                    onValueChange = {
                                        onSettingsChange(
                                            name,
                                            settings.copy(pitchShift = it.toDouble())
                                        )
                                    },
                                    valueRange = -1000f..1000f,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                // Merge into another voice
                                val otherVoices = voiceNames.filter { it != name }
                                if (otherVoices.isNotEmpty()) {
                                    var mergeExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { mergeExpanded = true },
                                            enabled = !isLoading,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Text("Объединить с...", style = MaterialTheme.typography.bodySmall)
                                        }
                                        DropdownMenu(
                                            expanded = mergeExpanded,
                                            onDismissRequest = { mergeExpanded = false },
                                        ) {
                                            otherVoices.forEach { target ->
                                                DropdownMenuItem(
                                                    text = { Text(target) },
                                                    onClick = {
                                                        mergeExpanded = false
                                                        onMergeVoice(name, target)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                Box(modifier = Modifier.weight(1f))

                                TextButton(
                                    onClick = { onRetryVoice(name) },
                                    enabled = !isLoading,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text("Переозвучить", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DropdownSelector(
    label: String,
    items: List<String>,
    selected: String,
    displayTransform: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            val display = displayTransform(selected)
            Text(if (label.isNotBlank()) "$label: $display" else display)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(displayTransform(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ChapterPlayerBar(
    isReady: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val alpha = if (isReady) 1f else 0.4f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(
            onClick = onPlayPause,
            enabled = isReady,
        ) {
            Text(
                text = if (isPlaying) "\u23F8" else "\u25B6",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.alpha(alpha),
            )
        }

        Text(
            text = formatTime(positionMs),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(48.dp).alpha(alpha),
            textAlign = TextAlign.End,
        )

        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
            onValueChange = { fraction ->
                onSeek((fraction * durationMs).toLong())
            },
            enabled = isReady,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = formatTime(durationMs),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(48.dp).alpha(alpha),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private suspend fun saveAudioFile(
    bytes: ByteArray,
    format: String,
    bookName: String = "",
    chapterName: String = ""
): String {
    return withContext(Dispatchers.IO) {
        val ext = when (format) {
            "oggopus" -> "ogg"
            else -> format
        }
        val dir = File(System.getProperty("user.home"), "SpeechHelper")
        dir.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val safeName = { s: String -> s.replace(Regex("[^\\w\\s\\-()\\[\\]а-яА-ЯёЁ]"), "_").trim() }
        val nameParts = listOfNotNull(
            bookName.takeIf { it.isNotBlank() }?.let { safeName(it) },
            chapterName.takeIf { it.isNotBlank() }?.let { safeName(it) },
            timestamp,
        )
        val fileName = nameParts.joinToString(" - ")
        val file = File(dir, "$fileName.$ext")
        file.writeBytes(bytes)
        file.absolutePath
    }
}
