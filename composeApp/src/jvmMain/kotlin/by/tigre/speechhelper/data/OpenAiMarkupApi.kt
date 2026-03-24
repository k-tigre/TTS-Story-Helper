package by.tigre.speechhelper.data

import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.domain.LlmConfig
import by.tigre.speechhelper.domain.LlmProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 240_000
        }
        install(ContentNegotiation) {
            json(this@OpenAiMarkupApi.json)
        }
    }

    private val BASE_SYSTEM_PROMPT = """
Нужно для озвучки через Yandex SpeechKit модифицировать текст, добавить акценты, разбить на голоса, используем TTS-разметка текста, паузы дополнительно если нужно указываем как <[small]>. Допустимые значения: tiny, small, medium, large, huge

Пример выделение голоса: "[voice_actor]Говорит профессор[/voice_actor], обычный голос, [голос2_нежный]Говорит леди[/голос2_нежный]" - нужно поставить начало и конец голоса - это важно! В конце голоса не забывай закрывающий тэг ставить.
Голосам можно добавлять эмоциональные оттенки, при необходимости добавлять еще голоса.
Рассказчик всегда один голос, он без эмоциональных отттенков.
Другие голоса добавляй только для диалогов.
Обязательно закрывай тэг голоса. Паузы оставляй внутри голоса.

ВАЖНО: Пример как обрабатывать диалоги:
— Это система подачи, — объяснил парень, видя растерянное лицо Тёмы. — Она слепая как крот. Если зазеваешься — прищемит так, что мало не покажется. Ты тот самый Серебряков?

должно получится в таком виде:
[voice_actor]
— Это система подачи, —
[/voice_actor]
[voice_main]
объяснил парень, видя растерянное лицо Тёмы.
[/voice_main]
[voice_actor]
Она слепая как крот. Если зазеваешься — прищемит так, что мало не покажется. Ты тот самый Серебряков?
[/voice_actor]

ВАЖНО: проверь диалоги

ВАЖНО: Нельзя менять содержание текста!Верни ТОЛЬКО размеченный текст, без пояснений.
"""

    private val DIALOG_FIX_PROMPT = """
Проверь размеченный текст. Убедись, что в диалогах слова автора (например "сказал он", "ответила она", "пробормотал он, отступая") вынесены в голос рассказчика, а не произносятся голосом персонажа.

Пример НЕПРАВИЛЬНОЙ разметки:
[voice_actor]
— Я не понимаю, о чём вы, — пробормотал он, отступая на шаг.
[/voice_actor]

Пример ПРАВИЛЬНОЙ разметки:
[voice_actor]
— Я не понимаю, о чём вы, —
[/voice_actor]
[voice_main]
пробормотал он, отступая на шаг.
[/voice_main]

Исправь все такие места. Верни ТОЛЬКО исправленный размеченный текст, без пояснений.
""".trimIndent()

    private fun buildSystemPrompt(existingVoices: Set<String>): String {
        if (existingVoices.isEmpty()) return BASE_SYSTEM_PROMPT
        val voicesList = existingVoices.joinToString(", ")
        return "$BASE_SYSTEM_PROMPT\n\nВ книге уже используются следующие голоса: $voicesList. Используй эти же имена голосов для разметки, не придумывай новые без необходимости."
    }

    fun autoMarkup(
        text: String,
        config: LlmConfig,
        existingVoices: Set<String> = emptySet(),
    ): Flow<MarkupResult> = flow {
        val systemPrompt = buildSystemPrompt(existingVoices)
        val chunks = AiMarkupApi.splitTextForAi(text)
        println("[OpenAiMarkup] Текст разбит на ${chunks.size} чанк(ов), model=${config.model}")

        if (chunks.size == 1) {
            emit(MarkupResult.InProgress("Авто-разметка..."))
            val result = requestMarkup(chunks[0], config, systemPrompt)
            emit(MarkupResult.Done(result))
            return@flow
        }

        val results = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            emit(MarkupResult.InProgress("Авто-разметка ${i + 1} из ${chunks.size}"))
            println("[OpenAiMarkup] Обработка чанка ${i + 1}/${chunks.size} (${chunk.length} символов)")
            val result = requestMarkup(chunk, config, systemPrompt)
            results.add(result)
        }
        println("[OpenAiMarkup] Все чанки обработаны")
        emit(MarkupResult.Done(results.joinToString("\n")))
    }

    fun fixDialog(
        text: String,
        config: LlmConfig,
    ): Flow<MarkupResult> = flow {
        val chunks = AiMarkupApi.splitTextForAi(text)
        println("[OpenAiFixDialog] Текст разбит на ${chunks.size} чанк(ов)")

        val results = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            emit(MarkupResult.InProgress("Исправление диалогов ${i + 1} из ${chunks.size}"))
            val result = sendChat(
                config = config,
                messages = listOf(
                    OaiChatMessage(role = "system", content = DIALOG_FIX_PROMPT),
                    OaiChatMessage(role = "user", content = chunk),
                ),
            )
            results.add(result.replace("```", ""))
        }
        emit(MarkupResult.Done(results.joinToString("\n")))
    }

    private suspend fun requestMarkup(
        text: String,
        config: LlmConfig,
        systemPrompt: String,
    ): String {
        println("[OpenAiMarkup] Запрос разметки (${text.length} символов)...")
        val result = sendChat(
            config = config,
            messages = listOf(
                OaiChatMessage(role = "system", content = systemPrompt),
                OaiChatMessage(role = "user", content = text),
            ),
        )
        println("[OpenAiMarkup] Получено ${result.length} символов")
        return result.replace("```", "")
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
            temperature = 0.5,
        )

        println("[OpenAiMarkup] -> POST $endpoint (model=$resolvedModel)")

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

        println("[OpenAiMarkup] <- HTTP ${response.status.value}")

        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            println("[OpenAiMarkup] ERROR: $body")
            throw AiMarkupException("LLM API error ${response.status.value}: $body")
        }

        val responseText = response.bodyAsText()
        val chatResponse = json.decodeFromString<OaiChatResponse>(responseText)
        val content = chatResponse.choices.firstOrNull()?.message?.content
            ?: throw AiMarkupException("No content in LLM response")
        println("[OpenAiMarkup] <- Получено ${content.length} символов")
        return content
    }
}
