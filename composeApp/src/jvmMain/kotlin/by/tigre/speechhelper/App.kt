package by.tigre.speechhelper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val API_VOICES = listOf(
    "alena", "filipp", "ermil", "jane", "madirus",
    "omazh", "zahar", "dasha", "julia", "lera",
    "masha", "marina", "alexander", "kirill", "anton",
)

private val LANGUAGES = listOf("ru-RU", "en-US", "kk-KK", "de-DE", "uz-UZ")

private val FORMATS = listOf("oggopus", "mp3", "lpcm")

@OptIn(ExperimentalMaterial3Api::class)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки API") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(token) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onTokenRefresh: () -> Unit) {
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var isSSML by remember { mutableStateOf(false) }
    var selectedVoice by remember { mutableStateOf(API_VOICES[0]) }
    var selectedLang by remember { mutableStateOf(LANGUAGES[0]) }
    var selectedFormat by remember { mutableStateOf(FORMATS[0]) }
    var speed by remember { mutableFloatStateOf(1.0f) }

    var isLoading by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    // Voice mapping: text voice name -> API voice name
    var voiceMapping by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showVoiceMappingDialog by remember { mutableStateOf(false) }
    var pendingSegments by remember { mutableStateOf<List<TextSegment>>(emptyList()) }

    if (showVoiceMappingDialog) {
        VoiceMappingDialog(
            voiceNames = voiceMapping.keys.toList(),
            currentMapping = voiceMapping,
            onConfirm = { mapping ->
                voiceMapping = mapping
                showVoiceMappingDialog = false
                // Launch synthesis with mapped voices
                scope.launch {
                    isLoading = true
                    statusMessage = ""
                    progressMessage = ""
                    try {
                        val audioParts = mutableListOf<ByteArray>()
                        for ((index, segment) in pendingSegments.withIndex()) {
                            val apiVoice = mapping[segment.voiceName] ?: API_VOICES[0]
                            progressMessage = "Обработка ${index + 1} из ${pendingSegments.size} (${segment.voiceName} -> $apiVoice)..."
                            val ssmlText = "<speak>${segment.text}</speak>"
                            val bytes = SpeechKitApi.synthesize(
                                text = ssmlText,
                                isSSML = true,
                                voice = apiVoice,
                                speed = segment.speed,
                                format = "mp3",
                                lang = selectedLang,
                                emotion = segment.emotion,
                                token = TokenStorage.iamToken,
                            )
                            audioParts.add(bytes)
                        }
                        progressMessage = "Склейка аудио..."
                        val combined = audioParts.reduce { acc, bytes -> acc + bytes }
                        val filePath = saveAudioFile(combined, "mp3")
                        statusMessage = "Сохранено (${pendingSegments.size} сегментов): $filePath"
                    } catch (e: Exception) {
                        statusMessage = "Ошибка: ${e.message}"
                    } finally {
                        isLoading = false
                        progressMessage = ""
                    }
                }
            },
            onDismiss = { showVoiceMappingDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpeechHelper") },
                actions = {
                    TextButton(onClick = onTokenRefresh) {
                        Text("Обновить токен")
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Текст для озвучивания") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 5,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isSSML, onCheckedChange = { isSSML = it })
                    Text("SSML")
                }

                val hasMarkers = TextParser.hasVoiceMarkers(text)

                if (!hasMarkers) {
                    DropdownSelector("Голос", API_VOICES, selectedVoice) { selectedVoice = it }
                    DropdownSelector("Формат", FORMATS, selectedFormat) { selectedFormat = it }
                }
                DropdownSelector("Язык", LANGUAGES, selectedLang) { selectedLang = it }
            }

            if (!TextParser.hasVoiceMarkers(text)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Скорость: ${"%.1f".format(speed)}")
                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        valueRange = 0.1f..3.0f,
                        modifier = Modifier.width(200.dp),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        val hasMarkers = TextParser.hasVoiceMarkers(text)
                        if (hasMarkers) {
                            val segments = TextParser.parse(text)
                            if (segments.isEmpty()) {
                                statusMessage = "Ошибка: не найдены сегменты текста"
                                return@Button
                            }
                            pendingSegments = segments
                            val names = segments.map { it.voiceName }.toSet()
                            // Preserve existing mappings, add new names with default
                            val newMapping = names.associateWith { name ->
                                voiceMapping[name] ?: API_VOICES[0]
                            }
                            voiceMapping = newMapping
                            showVoiceMappingDialog = true
                        } else {
                            scope.launch {
                                isLoading = true
                                statusMessage = ""
                                progressMessage = "Синтез речи..."
                                try {
                                    val audioBytes = SpeechKitApi.synthesize(
                                        text = if (isSSML) text else text,
                                        isSSML = isSSML,
                                        voice = selectedVoice,
                                        speed = speed,
                                        format = selectedFormat,
                                        lang = selectedLang,
                                        emotion = null,
                                        token = TokenStorage.iamToken,
                                    )
                                    val filePath = saveAudioFile(audioBytes, selectedFormat)
                                    statusMessage = "Сохранено: $filePath"
                                } catch (e: Exception) {
                                    statusMessage = "Ошибка: ${e.message}"
                                } finally {
                                    isLoading = false
                                    progressMessage = ""
                                }
                            }
                        }
                    },
                    enabled = text.isNotBlank() && !isLoading && TokenStorage.hasCredentials(),
                ) {
                    Text("Озвучить")
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    if (progressMessage.isNotBlank()) {
                        Text(progressMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

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
    }
}

@Composable
private fun VoiceMappingDialog(
    voiceNames: List<String>,
    currentMapping: Map<String, String>,
    onConfirm: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val mapping = remember(voiceNames) {
        mutableStateMapOf<String, String>().apply {
            putAll(currentMapping)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Соответствие голосов") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Укажите API-голос для каждого персонажа:",
                    style = MaterialTheme.typography.bodySmall,
                )
                voiceNames.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        DropdownSelector(
                            label = "",
                            items = API_VOICES,
                            selected = mapping[name] ?: API_VOICES[0],
                            onSelect = { mapping[name] = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(mapping.toMap()) }) {
                Text("Озвучить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun DropdownSelector(
    label: String,
    items: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (label.isNotBlank()) "$label: $selected" else selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

private suspend fun saveAudioFile(bytes: ByteArray, format: String): String {
    return withContext(Dispatchers.IO) {
        val ext = when (format) {
            "oggopus" -> "ogg"
            else -> format
        }
        val dir = File(System.getProperty("user.home"), "SpeechHelper")
        dir.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val file = File(dir, "speech_${timestamp}.$ext")
        file.writeBytes(bytes)
        file.absolutePath
    }
}
