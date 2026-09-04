package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent

class TuiLauncherSettingsSerializationTest {

    private fun roundTrip(state: TuiLauncherSettings.State): TuiLauncherSettings.State =
        XmlSerializer.deserialize(
            XmlSerializer.serialize(state, SkipDefaultsSerializationFilter()),
            TuiLauncherSettings.State::class.java,
        )

    @Test
    fun `sending the prompt and adding a separator are both on by default`() {
        val state = TuiLauncherSettings.State()

        assertTrue(state.submitPromptOnSend)
        assertTrue(state.appendPromptSeparatorOnSend)
    }

    @Test
    fun `turning both prompt sending flags off survives serialization`() {
        val restored = roundTrip(
            TuiLauncherSettings.State(submitPromptOnSend = false, appendPromptSeparatorOnSend = false)
        )

        assertFalse(restored.submitPromptOnSend)
        assertFalse(restored.appendPromptSeparatorOnSend)
    }

    @Test
    fun `turning one prompt sending flag off leaves the other one on`() {
        val restored = roundTrip(TuiLauncherSettings.State(submitPromptOnSend = false))

        assertFalse(restored.submitPromptOnSend)
        assertTrue(restored.appendPromptSeparatorOnSend)
    }

    @Test
    fun `leaving both prompt sending flags on survives serialization`() {
        val restored = roundTrip(TuiLauncherSettings.State())

        assertTrue(restored.submitPromptOnSend)
        assertTrue(restored.appendPromptSeparatorOnSend)
        assertTrue(restored.focusPromptFileAfterSend)
    }

    @Test
    fun `keeping focus in the prompt file after sending is on by default`() {
        assertTrue(TuiLauncherSettings.State().focusPromptFileAfterSend)
    }

    @Test
    fun `turning the prompt file focus off survives serialization and leaves the other flags on`() {
        val restored = roundTrip(TuiLauncherSettings.State(focusPromptFileAfterSend = false))

        assertFalse(restored.focusPromptFileAfterSend)
        assertTrue(restored.submitPromptOnSend)
        assertTrue(restored.appendPromptSeparatorOnSend)
    }

    @Test
    fun `the prompt box starts hidden at three tenths of the tab and shared by every app`() {
        val state = TuiLauncherSettings.State()

        assertFalse(state.promptBoxVisible)
        assertEquals(30, state.promptBoxPercent)
        assertFalse(state.rememberPromptBoxVisibilityPerApp)
        assertFalse(state.rememberPromptBoxSizePerApp)
    }

    @Test
    fun `the prompt box defaults are left out of the persisted state`() {
        val written = optionNames(TuiLauncherSettings.State())

        assertFalse(written.contains("promptBoxVisible"))
        assertFalse(written.contains("promptBoxPercent"))
        assertFalse(written.contains("rememberPromptBoxVisibilityPerApp"))
        assertFalse(written.contains("rememberPromptBoxSizePerApp"))
    }

    @Test
    fun `a shown prompt box and its size survive serialization`() {
        val restored = roundTrip(TuiLauncherSettings.State(promptBoxVisible = true, promptBoxPercent = 55))

        assertTrue(restored.promptBoxVisible)
        assertEquals(55, restored.promptBoxPercent)
    }

    @Test
    fun `both per app prompt box modes survive serialization`() {
        val restored = roundTrip(
            TuiLauncherSettings.State(
                rememberPromptBoxVisibilityPerApp = true,
                rememberPromptBoxSizePerApp = true,
            )
        )

        assertTrue(restored.rememberPromptBoxVisibilityPerApp)
        assertTrue(restored.rememberPromptBoxSizePerApp)
    }

    @Test
    fun `a per app prompt box state survives serialization and stays null while unset`() {
        val restored = roundTrip(
            TuiLauncherSettings.State(
                tuiApps = mutableListOf(
                    TuiAppConfig(name = "claude", command = "claude", promptBoxVisible = true, promptBoxPercent = 45),
                    TuiAppConfig(name = "codex", command = "codex"),
                )
            )
        )

        val claude = restored.tuiApps.first { it.name == "claude" }
        val codex = restored.tuiApps.first { it.name == "codex" }
        assertEquals(true, claude.promptBoxVisible)
        assertEquals(45, claude.promptBoxPercent)
        assertNull(codex.promptBoxVisible)
        assertNull(codex.promptBoxPercent)
    }

    @Test
    fun `the prompt box keeps the keyboard after a send by default`() {
        val state = TuiLauncherSettings.State()

        assertFalse(state.focusTuiAfterPromptBoxSend)
        assertFalse(optionNames(state).contains("focusTuiAfterPromptBoxSend"))
    }

    @Test
    fun `moving focus to the TUI after a send survives serialization`() {
        val restored = roundTrip(TuiLauncherSettings.State(focusTuiAfterPromptBoxSend = true))

        assertTrue(restored.focusTuiAfterPromptBoxSend)
        assertTrue(restored.submitPromptOnSend)
        assertFalse(restored.promptBoxVisible)
    }

    @Test
    fun `the prefix key for focusing the prompt box starts unset`() {
        val state = TuiLauncherSettings.State()

        assertNull(state.focusPromptBoxKeyCode)
        assertFalse(optionNames(state).contains("focusPromptBoxKeyCode"))
    }

    @Test
    fun `a prefix key for focusing the prompt box survives serialization`() {
        val restored = roundTrip(TuiLauncherSettings.State(focusPromptBoxKeyCode = KeyEvent.VK_B))

        assertEquals(KeyEvent.VK_B, restored.focusPromptBoxKeyCode)
    }

    private fun optionNames(state: TuiLauncherSettings.State): List<String> =
        XmlSerializer.serialize(state, SkipDefaultsSerializationFilter())
            .getChildren("option")
            .mapNotNull { it.getAttributeValue("name") }
}
