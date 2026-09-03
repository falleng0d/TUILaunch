package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
