package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.LlmConfig
import by.tigre.speechhelper.domain.LlmProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ModelsResponse(val data: List<ModelItem>)

@Serializable
private data class ModelItem(val id: String)

object LlmModelsApi {

    // Known Yandex Foundation Models (short names, folderId added at call time)
    val YANDEX_MODELS = listOf(
        "deepseek-v32",
        "yandexgpt",
        "yandexgpt-lite",
        "llama-lite",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 15_000 }
        install(ContentNegotiation) { json(this@LlmModelsApi.json) }
    }

    /**
     * Fetches available models from an OpenAI-compatible /models endpoint.
     * For [LlmProvider.YandexCloud] returns [YANDEX_MODELS] without an HTTP call.
     *
     * @param config current LLM configuration (provider, baseUrl, apiKey)
     * @param folderId Yandex Cloud folder ID (used only for YandexCloud provider)
     * @return sorted list of model IDs
     */
    suspend fun fetchModels(config: LlmConfig, folderId: String = ""): List<String> {
        if (config.provider == LlmProvider.YandexCloud) {
            return YANDEX_MODELS
        }

        val endpoint = config.baseUrl.trimEnd('/') + "/models"
        val response = client.get(endpoint) {
            if (config.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
        }

        val body = response.bodyAsText()
        val parsed = json.decodeFromString<ModelsResponse>(body)
        return parsed.data.map { it.id }.sorted()
    }
}
