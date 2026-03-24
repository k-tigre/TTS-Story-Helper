package by.tigre.speechhelper

import by.tigre.speechhelper.domain.LlmConfig
import by.tigre.speechhelper.domain.LlmProvider
import java.util.prefs.Preferences

object TokenStorage {
    private val prefs: Preferences = Preferences.userNodeForPackage(TokenStorage::class.java)

    private const val KEY_IAM_TOKEN = "iam_token"
    private const val KEY_FOLDER_ID = "folder_id_id"
    private const val KEY_LLM_PROVIDER = "llm_provider"
    private const val KEY_LLM_BASE_URL = "llm_base_url"
    private const val KEY_LLM_API_KEY = "llm_api_key"
    private const val KEY_LLM_MODEL = "llm_model"
    private const val KEY_FIRST_LAUNCH = "first_launch_done"

    var iamToken: String
        get() = prefs.get(KEY_IAM_TOKEN, "")
        set(value) = prefs.put(KEY_IAM_TOKEN, value)

    var folderId: String
        get() = prefs.get(KEY_FOLDER_ID, "")
        set(value) = prefs.put(KEY_FOLDER_ID, value)

    val isFirstLaunch: Boolean
        get() = !prefs.getBoolean(KEY_FIRST_LAUNCH, false)

    fun markFirstLaunchDone() {
        prefs.putBoolean(KEY_FIRST_LAUNCH, true)
    }

    var llmConfig: LlmConfig
        get() = LlmConfig(
            provider = runCatching { LlmProvider.valueOf(prefs.get(KEY_LLM_PROVIDER, "")) }.getOrDefault(LlmProvider.OpenAI),
            baseUrl = prefs.get(KEY_LLM_BASE_URL, ""),
            apiKey = prefs.get(KEY_LLM_API_KEY, ""),
            model = prefs.get(KEY_LLM_MODEL, ""),
        )
        set(value) {
            prefs.put(KEY_LLM_PROVIDER, value.provider.name)
            prefs.put(KEY_LLM_BASE_URL, value.baseUrl)
            prefs.put(KEY_LLM_API_KEY, value.apiKey)
            prefs.put(KEY_LLM_MODEL, value.model)
        }

    fun hasCredentials(): Boolean = iamToken.isNotBlank()

    fun clear() {
        prefs.remove(KEY_IAM_TOKEN)
        prefs.remove(KEY_FOLDER_ID)
    }
}
