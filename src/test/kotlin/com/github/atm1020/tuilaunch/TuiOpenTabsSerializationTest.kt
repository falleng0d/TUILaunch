package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.services.TuiOpenTabsService
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuiOpenTabsSerializationTest {

    private fun stateWithThreeTabs() = TuiOpenTabsService.State(
        mutableListOf(
            TuiSessionRecord("claude", "claude"),
            TuiSessionRecord("lazygit", "git", selected = true),
            TuiSessionRecord("claude", "claude 1"),
        )
    )

    private fun serialize(state: TuiOpenTabsService.State): Element =
        XmlSerializer.serialize(state, SkipDefaultsSerializationFilter())

    @Test
    fun `a state round trips through the xml serializer unchanged`() {
        val original = stateWithThreeTabs()

        val restored = XmlSerializer.deserialize(serialize(original), TuiOpenTabsService.State::class.java)

        assertEquals(original, restored)
    }

    @Test
    fun `the tab order survives serialization`() {
        val restored = XmlSerializer.deserialize(
            serialize(stateWithThreeTabs()),
            TuiOpenTabsService.State::class.java,
        )

        assertEquals(listOf("claude", "git", "claude 1"), restored.tabs.map { it.title })
    }

    @Test
    fun `exactly one selected flag survives serialization`() {
        val restored = XmlSerializer.deserialize(
            serialize(stateWithThreeTabs()),
            TuiOpenTabsService.State::class.java,
        )

        assertEquals(listOf("git"), restored.tabs.filter { it.selected }.map { it.title })
    }

    @Test
    fun `an unselected record writes no selected attribute`() {
        val xml = JDOMUtil.write(serialize(stateWithThreeTabs()))

        assertEquals(1, xml.split("selected").size - 1)
        assertTrue(xml.contains("""name="selected" value="true""""))
    }

    @Test
    fun `an empty state round trips to an empty tab list`() {
        val restored = XmlSerializer.deserialize(
            serialize(TuiOpenTabsService.State()),
            TuiOpenTabsService.State::class.java,
        )

        assertTrue(restored.tabs.isEmpty())
    }
}
