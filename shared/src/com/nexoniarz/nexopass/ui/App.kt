package com.nexoniarz.nexopass.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import com.nexoniarz.nexopass.app.AppState
import com.nexoniarz.nexopass.app.LocalPlatform
import com.nexoniarz.nexopass.app.Screen

/** The whole UI, same on the phone and the PC. */
@Composable
fun App(app: AppState) {
    val platform = LocalPlatform.current
    platform.AutoLock(app)

    val stage = when {
        !app.appUnlocked -> 0
        app.needsAppSetup -> 1
        else -> 2
    }
    AnimatedContent(
        targetState = stage,
        transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = .96f)) togetherWith fadeOut(tween(200)) },
        label = "stage",
    ) { st ->
        when (st) {
            0 -> LockScreen(app)
            1 -> AppUnlockSetupScreen(app)
            else -> UnlockedApp(app)
        }
    }
}

@Composable
private fun UnlockedApp(app: AppState) {
    val platform = LocalPlatform.current
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    platform.BackHandler(screen != Screen.List) { screen = Screen.List }
    val needs = app.needsMaster
    val session = app.session
    if (screen == Screen.NewAccount) {
        NewAccountScreen(app, onBack = { screen = Screen.List }, onDone = { screen = Screen.List })
        return
    }
    if (needs != null || session == null) {
        AccountMasterScreen(app, needs ?: app.current, onAddAccount = { screen = Screen.NewAccount })
        return
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState != Screen.List
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "screen",
    ) { s ->
        when (s) {
            Screen.List -> ListScreen(
                app, session,
                onOpen = { screen = Screen.Detail(it.site, it.user) },
                onAdd = { screen = Screen.Add },
                onSettings = { screen = Screen.Settings },
                onNewAccount = { screen = Screen.NewAccount },
            )
            Screen.Add -> AddScreen(session, onBack = { screen = Screen.List }, onOpen = { screen = Screen.Detail(it.site, it.user) })
            Screen.Settings -> SettingsScreen(app, session, onBack = { screen = Screen.List })
            Screen.NewAccount -> {}
            is Screen.Detail -> DetailScreen(session, s.site, s.user, onBack = { screen = Screen.List })
        }
    }
}
