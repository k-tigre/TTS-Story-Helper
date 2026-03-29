package by.tigre.speechhelper.data

import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.domain.LlmConfig
import by.tigre.speechhelper.domain.LlmProvider
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class OaiChatRequest(
    val model: String,
    val messages: List<OaiChatMessage>,
    val temperature: Double = 0.3,
)

@Serializable
private data class OaiChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class OaiChatResponse(
    val choices: List<OaiChoice>,
)

@Serializable
private data class OaiChoice(
    val message: OaiChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

object OpenAiMarkupApi {

    private const val MAX_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 1000L

    private val client = HttpClientProvider.markupClient
    private val json = HttpClientProvider.jsonInstance

    fun autoMarkup(
        text: String,
        config: LlmConfig,
        existingVoices: Set<String> = emptySet(),
    ): Flow<MarkupResult> = flow {
        val systemPrompt = MarkupSystemPrompts.autoMarkupPrompt(existingVoices)
        val chunks = AiMarkupApi.splitTextForAi(text)
        println("[OpenAiMarkup] Text split into ${chunks.size} chunk(s), model=${config.model}")

        if (chunks.size == 1) {
            emit(MarkupResult.InProgress("Auto markup..."))
            val result = requestMarkup(chunks[0], config, systemPrompt)
            emit(MarkupResult.Done(result))
            return@flow
        }

        val results = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            emit(MarkupResult.InProgress("Auto markup ${i + 1} of ${chunks.size}"))
            println("[OpenAiMarkup] Processing chunk ${i + 1}/${chunks.size} (${chunk.length} chars)")
            val result = requestMarkup(chunk, config, systemPrompt)
            results.add(result)
        }
        println("[OpenAiMarkup] All chunks processed")
        emit(MarkupResult.Done(results.joinToString("\n")))
    }

    fun fixDialog(
        text: String,
        config: LlmConfig,
    ): Flow<MarkupResult> = flow {
        val chunks = AiMarkupApi.splitTextForAi(text)
        println("[OpenAiFixDialog] Text split into ${chunks.size} chunk(s)")

        val results = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            emit(MarkupResult.InProgress("Fixing dialogs ${i + 1} of ${chunks.size}"))
            val result = sendChat(
                config = config,
                messages = listOf(
                    OaiChatMessage(role = "system", content = MarkupSystemPrompts.dialogFixPrompt),
                    OaiChatMessage(role = "user", content = chunk),
                ),
            )
            results.add(AiMarkupApi.postProcessAiMarkup(result.replace("```", "")))
        }
        emit(MarkupResult.Done(results.joinToString("\n")))
    }

    private suspend fun requestMarkup(
        text: String,
        config: LlmConfig,
        systemPrompt: String,
    ): String {
        println("[OpenAiMarkup] Markup request (${text.length} chars)...")
        val result = sendChat(
            config = config,
            messages = listOf(
                OaiChatMessage(role = "system", content = systemPrompt),
                OaiChatMessage(role = "user", content = text),
            ),
        )
        println("[OpenAiMarkup] Received ${result.length} chars")
        return AiMarkupApi.postProcessAiMarkup(result.replace("```", ""))
    }

    private suspend fun sendChat(
        config: LlmConfig,
        messages: List<OaiChatMessage>,
    ): String {
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"

        // For Yandex Cloud: construct full model URI and use Api-Key auth
        val resolvedModel = if (config.provider == LlmProvider.YandexCloud) {
            "gpt://${TokenStorage.folderId}/${config.model}/latest"
        } else {
            config.model
        }

        val request = OaiChatRequest(
            model = resolvedModel,
            messages = messages,
            temperature = 0.2,
        )

        val totalChars = messages.sumOf { it.content.length }
        println("[OpenAiMarkup] -> POST $endpoint (model=$resolvedModel, messages=${messages.size}, totalChars=$totalChars)")

        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRIES) {
            val startTime = System.currentTimeMillis()
            try {
                val response = client.post {
                    url(endpoint)
                    when (config.provider) {
                        LlmProvider.YandexCloud ->
                            header(HttpHeaders.Authorization, "Api-Key ${TokenStorage.iamToken}")
                        else ->
                            if (config.apiKey.isNotBlank())
                                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val elapsed = System.currentTimeMillis() - startTime

                println("[OpenAiMarkup] <- HTTP ${response.status.value} (attempt $attempt, ${elapsed}ms)")

                if (response.status != HttpStatusCode.OK) {
                    val body = response.bodyAsText()
                    println("[OpenAiMarkup] ERROR: $body")
                    throw AiMarkupException("LLM API error ${response.status.value}: $body")
                }

                val responseText = response.bodyAsText()
                val chatResponse = json.decodeFromString<OaiChatResponse>(responseText)
                val content = chatResponse.choices.firstOrNull()?.message?.content
                    ?: throw AiMarkupException("No content in LLM response")
                println("[OpenAiMarkup] <- Received ${content.length} chars")
                return content

            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                lastException = e
                println("[OpenAiMarkup] Error on attempt $attempt after ${elapsed}ms: ${e.message}")

                if (attempt < MAX_RETRIES) {
                    println("[OpenAiMarkup] Retrying in ${delayMs}ms...")
                    delay(delayMs)
                    delayMs *= 2 // exponential backoff
                }
            }
        }

        throw lastException ?: AiMarkupException("Unknown error after $MAX_RETRIES attempts")
    }
}
