package by.tigre.speechhelper

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

object SpeechKitApi {
    private const val ENDPOINT = "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize"

    private val client = HttpClient(CIO)

    suspend fun synthesize(
        text: String,
        isSSML: Boolean,
        voice: String,
        speed: Float,
        format: String,
        lang: String,
        emotion: String?,
        token: String,
    ): ByteArray {
        val response = client.submitForm(
            url = ENDPOINT,
            formParameters = parameters {
                if (isSSML) {
                    append("ssml", text)
                } else {
                    append("text", text)
                }
                append("voice", voice)
                append("speed", speed.toString())
                append("lang", lang)
                append("format", format)
                if (!emotion.isNullOrBlank()) {
                    append("emotion", emotion)
                }
            }
        ) {
            header(HttpHeaders.Authorization, "Api-Key $token")
        }

        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            println("API error ${response.status.value}: $body")
            throw SpeechKitException("API error ${response.status.value}: $body")
        }

        return response.readRawBytes()
    }
}

class SpeechKitException(message: String) : Exception(message)
