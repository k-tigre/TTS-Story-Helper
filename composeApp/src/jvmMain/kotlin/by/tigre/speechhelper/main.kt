package by.tigre.speechhelper

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

@OptIn(FlowPreview::class)
fun main() = application {
    val state = rememberWindowState(
        width = SessionStorage.windowWidth.dp,
        height = SessionStorage.windowHeight.dp,
    )

    Window(
        onCloseRequest = {
            SessionStorage.saveWindowSize(
                state.size.width.value.toInt(),
                state.size.height.value.toInt(),
            )
            exitApplication()
        },
        title = "SpeechHelper",
        state = state,
    ) {
        LaunchedEffect(state) {
            snapshotFlow { state.size }
                .debounce(500)
                .collect { size ->
                    SessionStorage.saveWindowSize(
                        size.width.value.toInt(),
                        size.height.value.toInt(),
                    )
                }
        }
        App()
    }
}
