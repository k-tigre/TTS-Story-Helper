package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.LocalTtsSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LocalTtsException(message: String) : Exception(message)

object LocalTtsApi {
    private const val CHUNK_LIMIT = 240

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

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
    ): Flow<SynthesisResult> = flow {
        val chunks = SynthesisChunking.splitText(text, CHUNK_LIMIT)
        if (chunks.size == 1) {
            emit(SynthesisResult.InProgress("Локальный синтез..."))
            val bytes = synthesizeChunk(chunks[0], speaker, settings, speed, pitchShift)
            emit(SynthesisResult.Done(bytes))
            return@flow
        }
        val wavParts = ArrayList<ByteArray>(chunks.size)
        for ((i, chunk) in chunks.withIndex()) {
            emit(SynthesisResult.InProgress("Локальный синтез: чанк ${i + 1} из ${chunks.size}"))
            wavParts.add(synthesizeChunk(chunk, speaker, settings, speed, pitchShift))
        }
        emit(SynthesisResult.Done(WavMerge.merge(wavParts)))
    }

    private suspend fun synthesizeChunk(
        text: String,
        speaker: String,
        settings: LocalTtsSettings,
        speed: Double,
        pitchShift: Double,
    ): ByteArray {
        val url = settings.baseUrl.trimEnd('/') + "/synthesize"
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
                ),
            )
        }
        if (response.status != HttpStatusCode.OK) {
            val err = runCatching { response.bodyAsText() }.getOrElse { response.status.description }
            throw LocalTtsException("Локальный TTS ${response.status.value}: $err")
        }
        val bytes = response.readRawBytes()
        if (bytes.isEmpty()) throw LocalTtsException("Пустой ответ локального TTS")
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
)
