package by.tigre.speechhelper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    var text by remember { mutableStateOf(SessionStorage.text) }
    var isSSML by remember { mutableStateOf(false) }
    var selectedVoice by remember { mutableStateOf(API_VOICES[0]) }
    var selectedLang by remember { mutableStateOf(LANGUAGES[0]) }
    var selectedFormat by remember { mutableStateOf(FORMATS[0]) }
    var speed by remember { mutableFloatStateOf(1.0f) }

    var isLoading by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    val voiceMapping = remember { mutableStateMapOf<String, String>() }

    // Load saved mapping on first composition
    LaunchedEffect(Unit) {
        val saved = SessionStorage.voiceMapping
        voiceMapping.putAll(saved)
    }

    // Save text on change
    LaunchedEffect(text) {
        SessionStorage.text = text
    }

    val hasMarkers = TextParser.hasVoiceMarkers(text)
    val detectedVoices: Set<String> = remember(text) {
        if (hasMarkers) TextParser.extractVoiceNames(text) else emptySet()
    }
    val voiceEmotions: Map<String, Set<String>> = remember(text) {
        if (hasMarkers) TextParser.extractVoiceEmotions(text) else emptyMap()
    }

    // Ensure all detected voices have a mapping
    LaunchedEffect(detectedVoices) {
        for (name in detectedVoices) {
            if (name !in voiceMapping) {
                voiceMapping[name] = API_VOICES[0]
            }
        }
        // Remove stale mappings
        val stale = voiceMapping.keys - detectedVoices
        for (key in stale) {
            voiceMapping.remove(key)
        }
    }

    fun saveVoiceMapping() {
        SessionStorage.voiceMapping = voiceMapping.toMap()
    }

    fun launchMultiVoiceSynthesis() {
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
                val audioParts = mutableListOf<ByteArray>()
                for ((index, segment) in segments.withIndex()) {
                    val apiVoice = voiceMapping[segment.voiceName] ?: API_VOICES[0]
                    progressMessage = "Обработка ${index + 1} из ${segments.size} (${segment.voiceName} -> $apiVoice)..."
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
                statusMessage = "Сохранено (${segments.size} сегментов): $filePath"
            } catch (e: Exception) {
                statusMessage = "Ошибка: ${e.message}"
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    fun launchSimpleSynthesis() {
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = "Синтез речи..."
            try {
                val audioBytes = SpeechKitApi.synthesize(
                    text = text,
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
            // Main area: text + voice panel side by side
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Текст для озвучивания") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    minLines = 5,
                )

                if (hasMarkers && detectedVoices.isNotEmpty()) {
                    VoiceMappingPanel(
                        voiceNames = detectedVoices.toList(),
                        voiceEmotions = voiceEmotions,
                        mapping = voiceMapping,
                        onMappingChange = { name, apiVoice ->
                            voiceMapping[name] = apiVoice
                            saveVoiceMapping()
                        },
                        modifier = Modifier.width(280.dp).fillMaxHeight(),
                    )
                }
            }

            // Controls row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isSSML, onCheckedChange = { isSSML = it })
                    Text("SSML")
                }

                if (!hasMarkers) {
                    DropdownSelector("Голос", API_VOICES, selectedVoice) { selectedVoice = it }
                    DropdownSelector("Формат", FORMATS, selectedFormat) { selectedFormat = it }
                }
                DropdownSelector("Язык", LANGUAGES, selectedLang) { selectedLang = it }
            }

            if (!hasMarkers) {
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

            // Action row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (hasMarkers) launchMultiVoiceSynthesis() else launchSimpleSynthesis()
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
private fun VoiceMappingPanel(
    voiceNames: List<String>,
    voiceEmotions: Map<String, Set<String>>,
    mapping: Map<String, String>,
    onMappingChange: (name: String, apiVoice: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Голоса", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()

            voiceNames.forEach { name ->
                val emotions = voiceEmotions[name]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        DropdownSelector(
                            label = "",
                            items = API_VOICES,
                            selected = mapping[name] ?: API_VOICES[0],
                            onSelect = { onMappingChange(name, it) }
                        )
                    }
                    if (!emotions.isNullOrEmpty()) {
                        Text(
                            text = emotions.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
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
