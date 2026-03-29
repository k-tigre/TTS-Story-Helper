package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.LlmConfig
import by.tigre.speechhelper.domain.LlmProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

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

    private val json = HttpClientProvider.jsonInstance
    private val client = HttpClientProvider.defaultClient

    /**
     * Fetches available models from an OpenAI-compatible /models endpoint.
     * For [LlmProvider.YandexCloud] returns [YANDEX_MODELS] without an HTTP call.
     *
     * @param config current LLM configuration (provider, baseUrl, apiKey)
     * @return sorted list of model IDs
     */
    suspend fun fetchModels(config: LlmConfig): List<String> {
        if (config.provider == LlmProvider.YandexCloud) {
            return YANDEX_MODELS
        }

        val endpoint = config.baseUrl.trimEnd('/') + "/models"
        println("[LlmModelsApi] -> GET $endpoint")
        val startTime = System.currentTimeMillis()
        val response = client.get(endpoint) {
            if (config.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
        }
        val elapsed = System.currentTimeMillis() - startTime

        println("[LlmModelsApi] <- HTTP ${response.status.value} (${elapsed}ms)")

        val body = response.bodyAsText()
        val parsed = json.decodeFromString<ModelsResponse>(body)
        return parsed.data.map { it.id }.sorted()
    }
}
