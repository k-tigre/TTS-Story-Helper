package by.tigre.speechhelper.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import by.tigre.speechhelper.data.ChapterAudioPlayer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class PlayerViewModel {

    val chapterPlayer = ChapterAudioPlayer()

    var playerIsPlaying by mutableStateOf(false)
    var playerPositionMs by mutableStateOf(0L)
    var playerDurationMs by mutableStateOf(0L)
    var playerReady by mutableStateOf(false)

    suspend fun bindChapterAudioFile(path: String?) {
        resetPlayerState()
        if (path == null) return
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

    suspend fun tickPositionWhilePlaying() {
        if (!playerIsPlaying) return
        while (coroutineContext.isActive && chapterPlayer.isPlaying) {
            playerPositionMs = chapterPlayer.currentPositionMs
            delay(200)
        }
        playerIsPlaying = chapterPlayer.isPlaying
    }

    fun togglePlayerPlayPause() {
        if (playerIsPlaying) {
            chapterPlayer.pause()
            playerIsPlaying = false
        } else {
            chapterPlayer.play()
            playerIsPlaying = true
        }
    }

    fun seekPlayer(posMs: Long) {
        chapterPlayer.seekTo(posMs)
        playerPositionMs = posMs
    }

    fun resetPlayerState() {
        playerIsPlaying = false
        playerPositionMs = 0L
        playerDurationMs = 0L
        playerReady = false
        chapterPlayer.close()
    }

    fun closeForChapterSwitch() {
        chapterPlayer.close()
        playerIsPlaying = false
    }
}
