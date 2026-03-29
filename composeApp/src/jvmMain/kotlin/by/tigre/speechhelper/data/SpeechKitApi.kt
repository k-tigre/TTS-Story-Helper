package by.tigre.speechhelper.data

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.util.Base64

sealed class SynthesisResult {
    data class InProgress(val message: String) : SynthesisResult()
    data class Done(val bytes: ByteArray) : SynthesisResult()
}

object SpeechKitApi {
    private const val ENDPOINT = "https://tts.api.cloud.yandex.net:443/tts/v3/utteranceSynthesis"
    private const val CHUNK_LIMIT = 240

    private val client = HttpClientProvider.defaultClient

    fun synthesize(
        text: String,
        voice: String,
        role: String?,
        speed: Double,
        pitchShift: Double,
        format: String,
        token: String,
    ): Flow<SynthesisResult> = flow {
        val chunks = SynthesisChunking.splitText(text, CHUNK_LIMIT)

        if (chunks.size == 1) {
            emit(SynthesisResult.InProgress("Speech synthesis..."))
            val bytes = synthesizeChunk(chunks[0], voice, role, speed, pitchShift, format, token)
            emit(SynthesisResult.Done(bytes))
            return@flow
        }

        val output = ByteArrayOutputStream()
        for ((i, chunk) in chunks.withIndex()) {
            emit(SynthesisResult.InProgress("Speech synthesis: chunk ${i + 1} of ${chunks.size}"))
            val bytes = synthesizeChunk(chunk, voice, role, speed, pitchShift, format, token)
            output.write(bytes)
        }
        emit(SynthesisResult.Done(output.toByteArray()))
    }

    private suspend fun synthesizeChunk(
        text: String,
        voice: String,
        role: String?,
        speed: Double,
        pitchShift: Double,
        format: String,
        token: String,
    ): ByteArray {
        val hints = buildList {
            add("""{"voice":"$voice"}""")
            add("""{"speed":$speed}""")
            if (!role.isNullOrBlank()) {
                add("""{"role":"$role"}""")
            }
            if (pitchShift != 0.0) {
                add("""{"pitch_shift":$pitchShift}""")
            }
        }
        val hintsJson = hints.joinToString(",")

        val outputSpec = when (format) {
            "mp3" -> """{"containerAudio":{"containerAudioType":"MP3"}}"""
            "ogg" -> """{"containerAudio":{"containerAudioType":"OGG_OPUS"}}"""
            "wav" -> """{"containerAudio":{"containerAudioType":"WAV"}}"""
            else -> """{"containerAudio":{"containerAudioType":"MP3"}}"""
        }

        val body = """{"text":"${escapeJson(text)}","outputAudioSpec":$outputSpec,"hints":[$hintsJson],"unsafeMode":true}"""

        println("[SpeechKit] -> POST $ENDPOINT (text=${text.length} chars, voice=$voice, format=$format)")
        val startTime = System.currentTimeMillis()
        val response = client.post(ENDPOINT) {
            header(HttpHeaders.Authorization, "Api-Key $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val elapsed = System.currentTimeMillis() - startTime

        println("[SpeechKit] <- HTTP ${response.status.value} (${elapsed}ms)")

        if (response.status != HttpStatusCode.OK) {
            val responseBody = response.bodyAsText()
            println("[SpeechKit] ERROR: $responseBody")
            throw SpeechKitException("API error ${response.status.value}: $responseBody")
        }

        val responseText = response.bodyAsText()
        val output = ByteArrayOutputStream()
        val chunkRegex = Regex(""""data"\s*:\s*"([^"]+)"""")

        for (match in chunkRegex.findAll(responseText)) {
            val base64Data = match.groupValues[1]
            output.write(Base64.getDecoder().decode(base64Data))
        }

        if (output.size() == 0) {
            throw SpeechKitException("Empty audio response")
        }

        return output.toByteArray()
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

class SpeechKitException(message: String) : Exception(message)
