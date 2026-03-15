package by.tigre.speechhelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

object AudioPlayer {
    private var currentClip: Clip? = null

    suspend fun play(wavBytes: ByteArray) {
        stop()
        withContext(Dispatchers.IO) {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes))
            val clip = AudioSystem.getClip()
            clip.open(stream)
            clip.start()
            currentClip = clip
        }
    }

    fun stop() {
        currentClip?.let {
            if (it.isRunning) it.stop()
            it.close()
        }
        currentClip = null
    }
}
