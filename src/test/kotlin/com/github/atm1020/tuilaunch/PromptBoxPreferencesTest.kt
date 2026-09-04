package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.services.PromptBoxPreferences
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBoxPreferencesTest {

    private val state = TuiLauncherSettings.State(
        tuiApps = mutableListOf(
            TuiAppConfig(name = "claude", command = "claude"),
            TuiAppConfig(name = "codex", command = "codex"),
        )
    )

    private val preferences = PromptBoxPreferences { state }

    private fun appConfig(name: String): TuiAppConfig = state.tuiApps.first { it.name == name }

    @Test
    fun `global mode reads and writes the shared visibility`() {
        preferences.rememberVisibility("claude", true)

        assertTrue(state.promptBoxVisible)
        assertTrue(preferences.visibilityFor("claude"))
        assertTrue(preferences.visibilityFor("codex"))
        assertNull(appConfig("claude").promptBoxVisible)
    }

    @Test
    fun `global mode reads and writes the shared size`() {
        preferences.rememberPercent("claude", 55)

        assertEquals(55, state.promptBoxPercent)
        assertEquals(55, preferences.percentFor("codex"))
        assertNull(appConfig("claude").promptBoxPercent)
    }

    @Test
    fun `per app mode writes the visibility on the app it belongs to`() {
        state.rememberPromptBoxVisibilityPerApp = true

        preferences.rememberVisibility("claude", true)

        assertTrue(appConfig("claude").promptBoxVisible == true)
        assertNull(appConfig("codex").promptBoxVisible)
        assertFalse(state.promptBoxVisible)
        assertTrue(preferences.visibilityFor("claude"))
        assertFalse(preferences.visibilityFor("codex"))
    }

    @Test
    fun `per app mode writes the size on the app it belongs to`() {
        state.rememberPromptBoxSizePerApp = true

        preferences.rememberPercent("claude", 65)

        assertEquals(65, appConfig("claude").promptBoxPercent)
        assertNull(appConfig("codex").promptBoxPercent)
        assertEquals(30, state.promptBoxPercent)
        assertEquals(65, preferences.percentFor("claude"))
    }

    @Test
    fun `per app mode falls back to the global value while the app has no opinion`() {
        state.promptBoxVisible = true
        state.promptBoxPercent = 45
        state.rememberPromptBoxVisibilityPerApp = true
        state.rememberPromptBoxSizePerApp = true

        assertTrue(preferences.visibilityFor("claude"))
        assertEquals(45, preferences.percentFor("claude"))
    }

    @Test
    fun `global mode ignores what an app remembered earlier`() {
        appConfig("claude").promptBoxVisible = true
        appConfig("claude").promptBoxPercent = 80

        assertFalse(preferences.visibilityFor("claude"))
        assertEquals(30, preferences.percentFor("claude"))
    }

    @Test
    fun `an app that is no longer configured drops its per app writes`() {
        state.rememberPromptBoxVisibilityPerApp = true
        state.rememberPromptBoxSizePerApp = true

        preferences.rememberVisibility("removed", true)
        preferences.rememberPercent("removed", 70)

        assertFalse(state.promptBoxVisible)
        assertEquals(30, state.promptBoxPercent)
        assertFalse(preferences.visibilityFor("removed"))
        assertEquals(30, preferences.percentFor("removed"))
    }

    @Test
    fun `the modes are reported separately`() {
        state.rememberPromptBoxVisibilityPerApp = true

        assertTrue(preferences.visibilityIsPerApp())
        assertFalse(preferences.sizeIsPerApp())
    }
}
