package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.services.TuiOpenTabsService
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RECORD_TAB_UUID = "b7c1e0d4-3a52-4f19-8c7d-2e6f5a9b1c30"
private const val RECORD_OTHER_TAB_UUID = "6a1f0c22-9b74-4a0e-8d3f-2b5c7e91a004"
private const val RECORD_CODEX_SESSION_ID = "019a4f3c-7b21-7cd0-9e55-3f1b2a6d8c47"

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
    fun `a tab uuid and its agent session id round trip`() {
        val original = TuiOpenTabsService.State(
            mutableListOf(
                TuiSessionRecord("claude", "claude", true, RECORD_TAB_UUID, RECORD_TAB_UUID, "CLAUDE"),
                TuiSessionRecord(
                    "codex",
                    "codex",
                    false,
                    RECORD_OTHER_TAB_UUID,
                    RECORD_CODEX_SESSION_ID,
                    "CODEX",
                ),
            )
        )

        val restored = XmlSerializer.deserialize(serialize(original), TuiOpenTabsService.State::class.java)

        assertEquals(original, restored)
        assertEquals(listOf("CLAUDE", "CODEX"), restored.tabs.map { it.agentCliKind })
    }

    @Test
    fun `a record without a session identity writes neither option`() {
        val xml = JDOMUtil.write(serialize(stateWithThreeTabs()))

        assertFalse(xml.contains("tabUuid"))
        assertFalse(xml.contains("agentSessionId"))
        assertFalse(xml.contains("agentCliKind"))
    }

    @Test
    fun `a workspace file written before tabs carried an identity loads with nulls`() {
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(
                """
                <State>
                  <option name="tabs">
                    <list>
                      <TuiSessionRecord>
                        <option name="appName" value="claude" />
                        <option name="title" value="claude" />
                      </TuiSessionRecord>
                      <TuiSessionRecord>
                        <option name="appName" value="lazygit" />
                        <option name="title" value="git" />
                        <option name="selected" value="true" />
                      </TuiSessionRecord>
                    </list>
                  </option>
                </State>
                """.trimIndent()
            ),
            TuiOpenTabsService.State::class.java,
        )

        assertEquals(listOf("claude", "git"), restored.tabs.map { it.title })
        assertEquals(listOf("git"), restored.tabs.filter { it.selected }.map { it.title })
        assertTrue(
            restored.tabs.all { it.tabUuid == null && it.agentSessionId == null && it.agentCliKind == null }
        )
    }

    @Test
    fun `a record written before the cli kind was stored keeps its session id`() {
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(
                """
                <State>
                  <option name="tabs">
                    <list>
                      <TuiSessionRecord>
                        <option name="appName" value="codex" />
                        <option name="title" value="codex" />
                        <option name="tabUuid" value="$RECORD_TAB_UUID" />
                        <option name="agentSessionId" value="$RECORD_CODEX_SESSION_ID" />
                      </TuiSessionRecord>
                    </list>
                  </option>
                </State>
                """.trimIndent()
            ),
            TuiOpenTabsService.State::class.java,
        )

        val record = restored.tabs.single()
        assertEquals(RECORD_TAB_UUID, record.tabUuid)
        assertEquals(RECORD_CODEX_SESSION_ID, record.agentSessionId)
        assertNull(record.agentCliKind)
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
