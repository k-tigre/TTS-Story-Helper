package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.LocalTtsSettings
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class LocalTtsException(message: String) : Exception(message)

object LocalTtsApi {
    private const val CHUNK_LIMIT = 240

    private val json = HttpClientProvider.jsonInstance
    private val client = HttpClientProvider.defaultClient

    suspend fun checkHealth(baseUrl: String): Boolean {
        val root = baseUrl.trimEnd('/')
        return try {
            val r = client.get("$root/health")
            r.status == HttpStatusCode.OK
        } catch (_: Exception) {
            false
        }
    }

    fun synthesize(
        text: String,
        speaker: String,
        settings: LocalTtsSettings,
        speed: Double = 1.0,
        pitchShift: Double = 0.0,
        outputFormat: String,
    ): Flow<SynthesisResult> = flow {
        val chunks = SynthesisChunking.splitText(text, CHUNK_LIMIT)
        if (chunks.size == 1) {
            emit(SynthesisResult.InProgress("Local synthesis..."))
            val bytes = synthesizeChunk(chunks[0], speaker, settings, speed, pitchShift, outputFormat)
            emit(SynthesisResult.Done(bytes))
            return@flow
        }
        val parts = ArrayList<ByteArray>(chunks.size)
        for ((i, chunk) in chunks.withIndex()) {
            emit(SynthesisResult.InProgress("Local synthesis: chunk ${i + 1} of ${chunks.size}"))
            parts.add(synthesizeChunk(chunk, speaker, settings, speed, pitchShift, outputFormat))
        }
        val merged =
            if (outputFormat == "wav") {
                WavMerge.merge(parts)
            } else {
                parts.reduce { acc, b -> acc + b }
            }
        emit(SynthesisResult.Done(merged))
    }

    private suspend fun synthesizeChunk(
        text: String,
        speaker: String,
        settings: LocalTtsSettings,
        speed: Double,
        pitchShift: Double,
        outputFormat: String,
    ): ByteArray {
        val url = settings.baseUrl.trimEnd('/') + "/synthesize"
        println("[LocalTts] -> POST $url (text=${text.length} chars, speaker=$speaker, format=$outputFormat)")
        val startTime = System.currentTimeMillis()
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(
                LocalSynRequest(
                    text = text,
                    speaker = speaker,
                    sampleRate = settings.sampleRate,
                    modelId = settings.modelId,
                    speed = speed,
                    pitchShift = pitchShift,
                    outputFormat = outputFormat,
                ),
            )
        }
        val elapsed = System.currentTimeMillis() - startTime
        println("[LocalTts] <- HTTP ${response.status.value} (${elapsed}ms)")

        if (response.status != HttpStatusCode.OK) {
            val err = runCatching { response.bodyAsText() }.getOrElse { response.status.description }
            throw LocalTtsException("Local TTS ${response.status.value}: $err")
        }
        val bytes = response.readRawBytes()
        if (bytes.isEmpty()) throw LocalTtsException("Empty response from local TTS")
        return bytes
    }
}

@Serializable
private data class LocalSynRequest(
    val text: String,
    val speaker: String,
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("model_id") val modelId: String,
    val speed: Double = 1.0,
    @SerialName("pitch_shift") val pitchShift: Double = 0.0,
    @SerialName("output_format") val outputFormat: String,
)
