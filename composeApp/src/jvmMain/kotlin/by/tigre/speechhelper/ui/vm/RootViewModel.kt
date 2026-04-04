package by.tigre.speechhelper.ui.vm

import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Корень UI: глобальные диалоги, дочерние VM (главный координатор, плеер).
 * «Навигация» по приложению — смена главы и вкладок внутри [MainViewModel]; при расширении
 * можно добавить сюда явный стек экранов.
 */
class RootViewModel(scope: CoroutineScope) {

    val dialogs = AppDialogState()
    val player = PlayerViewModel()
    val main = MainViewModel(scope, dialogs, player)

    init {
        scope.launch {
            if (TokenStorage.isFirstLaunch) {
                dialogs.showHelpDialog = true
                TokenStorage.markFirstLaunchDone()
            }
        }
    }
}
