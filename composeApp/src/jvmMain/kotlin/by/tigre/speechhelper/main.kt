package by.tigre.speechhelper

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.ui.App
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@OptIn(FlowPreview::class)
fun main() {
    SessionStorage.warmUp()
    val initialWindowW = SessionStorage.windowWidth
    val initialWindowH = SessionStorage.windowHeight
    application {
        val state = rememberWindowState(
            width = initialWindowW.dp,
            height = initialWindowH.dp,
        )

        val iconPainter = try {
            val iconBytes = Thread.currentThread().contextClassLoader.getResourceAsStream("app_icon.png")!!.readBytes()
            BitmapPainter(Image.makeFromEncoded(iconBytes).toComposeImageBitmap())
        } catch (_: Exception) {
            null
        }

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
            icon = iconPainter,
        ) {
            LaunchedEffect(state) {
                snapshotFlow { state.size }
                    .debounce(500)
                    .collect { size ->
                        withContext(SessionStorage.databaseDispatcher) {
                            SessionStorage.saveWindowSize(
                                size.width.value.toInt(),
                                size.height.value.toInt(),
                            )
                        }
                    }
            }
            App()
        }
    }
}
