package by.tigre.speechhelper

import java.util.prefs.Preferences

object TokenStorage {
    private val prefs: Preferences = Preferences.userNodeForPackage(TokenStorage::class.java)

    private const val KEY_IAM_TOKEN = "iam_token"
    private const val KEY_FOLDER_ID = "folder_id"

    var iamToken: String
        get() = prefs.get(KEY_IAM_TOKEN, "")
        set(value) = prefs.put(KEY_IAM_TOKEN, value)

    fun hasCredentials(): Boolean = iamToken.isNotBlank()

    fun clear() {
        prefs.remove(KEY_IAM_TOKEN)
        prefs.remove(KEY_FOLDER_ID)
    }
}
