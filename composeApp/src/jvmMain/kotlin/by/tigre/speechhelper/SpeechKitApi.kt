package by.tigre.speechhelper

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.ByteArrayOutputStream
import java.util.Base64

object SpeechKitApi {
    private const val ENDPOINT = "https://tts.api.cloud.yandex.net:443/tts/v3/utteranceSynthesis"

    private val client = HttpClient(CIO)

    suspend fun synthesize(
        text: String,
        voice: String,
        role: String?,
        speed: Double,
        format: String,
        token: String,
    ): ByteArray {
        val hints = buildList {
            add("""{"voice":"$voice"}""")
            add("""{"speed":$speed}""")
            if (!role.isNullOrBlank()) {
                add("""{"role":"$role"}""")
            }
        }
        val hintsJson = hints.joinToString(",")

        val outputSpec = when (format) {
            "mp3" -> """{"containerAudio":{"containerAudioType":"MP3"}}"""
            "ogg" -> """{"containerAudio":{"containerAudioType":"OGG_OPUS"}}"""
            "wav" -> """{"containerAudio":{"containerAudioType":"WAV"}}"""
            else -> """{"containerAudio":{"containerAudioType":"MP3"}}"""
        }

        val body = """{"text":"${escapeJson(text)}","outputAudioSpec":$outputSpec,"hints":[$hintsJson]}"""

        val response = client.post(ENDPOINT) {
            header(HttpHeaders.Authorization, "Api-Key $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        if (response.status != HttpStatusCode.OK) {
            val responseBody = response.bodyAsText()
            println("API error ${response.status.value}: $responseBody")
            throw SpeechKitException("API error ${response.status.value}: $responseBody")
        }

        // Response is newline-delimited JSON objects with base64 audio chunks
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
