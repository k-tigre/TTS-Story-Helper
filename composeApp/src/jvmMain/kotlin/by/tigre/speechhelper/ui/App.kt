package by.tigre.speechhelper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import by.tigre.speechhelper.TokenStorage

@Composable
fun App() {
    var showTokenDialog by remember { mutableStateOf(!TokenStorage.hasCredentials()) }

    MaterialTheme {
        if (showTokenDialog) {
            TokenDialog(
                onDismiss = {
                    if (TokenStorage.hasCredentials()) showTokenDialog = false
                },
                onSave = { token: String ->
                    TokenStorage.iamToken = token
                    showTokenDialog = false
                },
            )
        }

        MainScreen(onTokenRefresh = { showTokenDialog = true })
    }
}
