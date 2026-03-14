package by.tigre.speechhelper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jdk.jfr.Enabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
                    label = { Text("IAM Token") },
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

private val voices = listOf(
    "alena", "filipp", "ermil", "jane", "madirus",
    "omazh", "zahar", "dasha", "julia", "lera",
    "masha", "marina", "alexander", "kirill", "anton",
)

private val languages = listOf("ru-RU", "en-US", "kk-KK", "de-DE", "uz-UZ")

private val formats = listOf("oggopus", "mp3", "lpcm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onTokenRefresh: () -> Unit) {
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var isSSML by remember { mutableStateOf(false) }
    var selectedVoice by remember { mutableStateOf(voices[0]) }
    var selectedLang by remember { mutableStateOf(languages[0]) }
    var selectedFormat by remember { mutableStateOf(formats[0]) }
    var speed by remember { mutableFloatStateOf(1.0f) }

    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

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

                if (isSSML.not()) {
                    DropdownSelector("Голос", voices, selectedVoice) { selectedVoice = it }
                }

                DropdownSelector("Язык", languages, selectedLang) { selectedLang = it }
                DropdownSelector("Формат", formats, selectedFormat) { selectedFormat = it }
            }

            if (isSSML.not()) {
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
                        scope.launch {
                            isLoading = true
                            statusMessage = ""
                            try {
                                val audioBytes = SpeechKitApi.synthesize(
                                    text = text,
                                    isSSML = isSSML,
                                    voice = selectedVoice,
                                    speed = speed,
                                    format = selectedFormat,
                                    lang = selectedLang,
                                    token = TokenStorage.iamToken,
                                )
                                val filePath = saveAudioFile(audioBytes, selectedFormat)
                                statusMessage = "Сохранено: $filePath"
                            } catch (e: Exception) {
                                statusMessage = "Ошибка: ${e.message}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = text.isNotBlank() && !isLoading && TokenStorage.hasCredentials(),
                ) {
                    Text("Озвучить")
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
private fun DropdownSelector(
    label: String,
    items: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: $selected")
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
