package by.tigre.speechhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.domain.ChapterInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSelector(
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
                                fontWeight = if (chapter.id == currentChapterId) FontWeight.Bold else null,
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
                    "\u2716",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun DropdownSelector(
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
                    },
                )
            }
        }
    }
}

@Composable
fun ChapterPlayerBar(
    isReady: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (isReady) 1f else 0.4f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        IconButton(onClick = onPlayPause, enabled = isReady) {
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
            onValueChange = { fraction -> onSeek((fraction * durationMs).toLong()) },
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

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
