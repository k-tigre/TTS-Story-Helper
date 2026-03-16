package by.tigre.speechhelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

class ChapterAudioPlayer {
    private var clip: Clip? = null
    private var onPlaybackFinished: (() -> Unit)? = null

    val isOpen: Boolean get() = clip != null
    val isPlaying: Boolean get() = clip?.isRunning == true

    val durationMs: Long
        get() {
            val c = clip ?: return 0L
            return c.microsecondLength / 1000
        }

    val currentPositionMs: Long
        get() {
            val c = clip ?: return 0L
            return c.microsecondPosition / 1000
        }

    suspend fun open(filePath: String, onFinished: () -> Unit = {}) {
        close()
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) return@withContext
            val audioStream = AudioSystem.getAudioInputStream(file)
            val baseFormat = audioStream.format
            val decodedFormat = javax.sound.sampled.AudioFormat(
                javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.sampleRate,
                16,
                baseFormat.channels,
                baseFormat.channels * 2,
                baseFormat.sampleRate,
                false
            )
            val decodedStream = AudioSystem.getAudioInputStream(decodedFormat, audioStream)
            val newClip = AudioSystem.getClip()
            newClip.open(decodedStream)
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP && newClip.microsecondPosition >= newClip.microsecondLength) {
                    onFinished()
                }
            }
            clip = newClip
            onPlaybackFinished = onFinished
        }
    }

    fun play() {
        clip?.start()
    }

    fun pause() {
        clip?.stop()
    }

    fun seekTo(positionMs: Long) {
        clip?.microsecondPosition = positionMs * 1000
    }

    fun close() {
        clip?.let {
            if (it.isRunning) it.stop()
            it.close()
        }
        clip = null
        onPlaybackFinished = null
    }
}
