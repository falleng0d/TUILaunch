package com.github.atm1020.tuilaunch.services

import com.github.atm1020.tuilaunch.model.TuiAppConfig

internal class PromptBoxPreferences(private val settings: () -> TuiLauncherSettings.State) {

    fun visibilityIsPerApp(): Boolean = settings().rememberPromptBoxVisibilityPerApp

    fun sizeIsPerApp(): Boolean = settings().rememberPromptBoxSizePerApp

    fun visibilityFor(appName: String): Boolean {
        val state = settings()
        if (!state.rememberPromptBoxVisibilityPerApp) return state.promptBoxVisible
        return configFor(appName)?.promptBoxVisible ?: state.promptBoxVisible
    }

    fun rememberVisibility(appName: String, visible: Boolean) {
        val state = settings()
        if (state.rememberPromptBoxVisibilityPerApp) {
            configFor(appName)?.promptBoxVisible = visible
        } else {
            state.promptBoxVisible = visible
        }
    }

    fun percentFor(appName: String): Int {
        val state = settings()
        if (!state.rememberPromptBoxSizePerApp) return state.promptBoxPercent
        return configFor(appName)?.promptBoxPercent ?: state.promptBoxPercent
    }

    fun rememberPercent(appName: String, percent: Int) {
        val state = settings()
        if (state.rememberPromptBoxSizePerApp) {
            configFor(appName)?.promptBoxPercent = percent
        } else {
            state.promptBoxPercent = percent
        }
    }

    private fun configFor(appName: String): TuiAppConfig? =
        settings().tuiApps.firstOrNull { it.name == appName }
}
