package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.ClaudeSessionStrategy
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.services.TuiOpenTabsService
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.github.atm1020.tuilaunch.toolwindow.readEditorTabRecord
import com.github.atm1020.tuilaunch.toolwindow.writeEditorTabRecord
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

private const val TOOL_WINDOW_TAB_UUID = "0d6f2c1a-5b3e-4c8d-9a7f-1e2b3c4d5e6f"
private const val EDITOR_TAB_UUID = "7a8b9c0d-1e2f-4a3b-8c5d-6e7f8a9b0c1d"
private const val GONE_TAB_UUID = "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f"
private const val STATE_DELETION_TIMEOUT_SECONDS = 30

class TuiEditorTabRestoreTest : BasePlatformTestCase() {

    private val strategy = RecordingSessionStrategy(listOf(ClaudeSessionStrategy.RESUME_FLAG, EDITOR_TAB_UUID))
    private lateinit var environment: AgentSessionEnvironment
    private val toolWindowRecord = TuiSessionRecord("claude", "claude", true, TOOL_WINDOW_TAB_UUID, null, "CLAUDE")
    private val editorRecord =
        TuiSessionRecord("claude", "reviewer", false, EDITOR_TAB_UUID, EDITOR_TAB_UUID, "CLAUDE", inEditor = true)

    override fun setUp() {
        super.setUp()
        environment = temporaryAgentSessionEnvironment()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps = mutableListOf(TuiAppConfig("claude", "claude"))
            restoreOpenTabs = true
            restoreAgentSessions = true
            promptBoxVisible = false
        }
        TuiOpenTabsService.getInstance(project).replaceTabs(emptyList())
    }

    override fun tearDown() {
        try {
            TuiLauncherSettings.getInstance().state.apply {
                tuiApps.clear()
                restoreOpenTabs = false
            }
            TuiOpenTabsService.getInstance(project).replaceTabs(emptyList())
        } finally {
            super.tearDown()
        }
    }

    private fun newService(sessionFactory: TerminalSessionFactory): Pair<TuiAppLaunchService, FakeHost> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Disposer.register(testRootDisposable) { scope.cancel() }
        val service = TuiAppLaunchService(project, scope)
        val host = FakeHost().apply { dockedHorizontally = true }
        service.host = host
        service.sessionFactory = sessionFactory
        service.agentSessionEnvironment = { environment }
        service.agentSessionStrategies = { _, _, _ -> strategy }
        service.projectIsTrusted = { true }
        return service to host
    }

    private fun saveTabs(vararg records: TuiSessionRecord) {
        TuiOpenTabsService.getInstance(project).replaceTabs(records.toList())
    }

    private fun savedTabs(): List<TuiSessionRecord> = TuiOpenTabsService.getInstance(project).state.tabs

    private fun stateFileOf(tabUuid: String): Path =
        environment.stateDirectory.resolve(ClaudeSessionStrategy.DIRECTORY_NAME).resolve("$tabUuid.json")

    private fun writeStateFileOf(tabUuid: String): Path = stateFileOf(tabUuid).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, """{"session_id":"$tabUuid"}""")
    }

    private fun splitterOf(host: FakeHost, handle: Any): Splitter = host.componentOf(handle) as Splitter

    fun testAnEditorTabRecordRoundTripsThroughItsXml() {
        val restored = readEditorTabRecord(writeEditorTabRecord(editorRecord))

        assertEquals(editorRecord, restored)
        assertNull(readEditorTabRecord(Element("somethingElse")))
    }

    fun testASessionInTheEditorIsSavedAsAnEditorRecordAndOnlyServesItsXmlWhileTabsAreRestored() {
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))
        service.launchNew("claude", "claude")
        service.launchNew("claude", "claude")
        val moved = host.moveTabToEditor(0) { service.tabMovedToEditor(it) }

        assertEquals(listOf(false, true), savedTabs().map { it.inEditor })
        assertEquals(savedTabs()[1].tabUuid, host.savedRecordOf(moved)?.tabUuid)

        TuiLauncherSettings.getInstance().state.restoreOpenTabs = false

        assertNull(host.savedRecordOf(moved))
    }

    fun testTheToolWindowRestoreSkipsEditorRecordsWhileTheCleanupKeepsTheirState() {
        saveTabs(toolWindowRecord, editorRecord)
        val stateFiles = listOf(TOOL_WINDOW_TAB_UUID, EDITOR_TAB_UUID).map { writeStateFileOf(it) }
        val goneStateFile = writeStateFileOf(GONE_TAB_UUID)
        val factory = FakeFactory(listOf(FakeSession()))
        val (service, host) = newService(factory)

        service.restoreSavedTabs()
        PlatformTestUtil.waitWithEventsDispatching(
            "The state of the tab that is gone was never deleted",
            { !Files.exists(goneStateFile) },
            STATE_DELETION_TIMEOUT_SECONDS,
        )

        assertTrue(stateFiles.all { Files.exists(it) })
        assertEquals(1, factory.commands.size)
        assertEquals(1, host.tabs.size)
        assertTrue(host.editorTabs.isEmpty())
        assertEquals(listOf(TOOL_WINDOW_TAB_UUID, EDITOR_TAB_UUID), savedTabs().map { it.tabUuid })
        assertEquals(listOf(false, true), savedTabs().map { it.inEditor })
    }

    fun testARestoredEditorTabResumesItsAgentSessionInsideItsOwnEditorTab() {
        saveTabs(toolWindowRecord, editorRecord)
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        val handle = requireNotNull(service.restoreEditorTab(editorRecord))

        assertEquals(listOf(handle), host.editorTabs)
        assertEquals(1, host.tabs.size)
        assertTrue(splitterOf(host, handle).isVertical)
        assertEquals(2, strategy.calls.count { it == "restoreArguments" })
        assertEquals(EDITOR_TAB_UUID, host.savedRecordOf(handle)?.tabUuid)
        assertEquals(
            listOf(TOOL_WINDOW_TAB_UUID to false, EDITOR_TAB_UUID to true),
            savedTabs().map { it.tabUuid to it.inEditor },
        )
    }

    fun testAnEditorTabOfARemovedAppIsDroppedAndAnUntrustedProjectKeepsItsRecord() {
        saveTabs(editorRecord)
        val (service, _) = newService(FakeFactory(emptyList()))
        service.projectIsTrusted = { false }

        assertNull(service.restoreEditorTab(editorRecord))
        assertEquals(listOf(EDITOR_TAB_UUID), savedTabs().map { it.tabUuid })

        TuiLauncherSettings.getInstance().state.tuiApps.clear()

        assertNull(service.restoreEditorTab(editorRecord))
        assertTrue(savedTabs().isEmpty())
    }

    fun testClosingAnEditorTabBeforeItWasShownDropsItsRecordAndItsAgentState() {
        saveTabs(toolWindowRecord, editorRecord)
        val (service, _) = newService(FakeFactory(listOf(FakeSession())))
        service.restoreSavedTabs()

        service.forgetEditorTabClosedBeforeItWasShown(editorRecord)

        assertEquals(listOf(TOOL_WINDOW_TAB_UUID), savedTabs().map { it.tabUuid })
        assertTrue("cleanUp" in strategy.calls)
    }

    fun testTheProjectCloseKeepsTheRecordAndStateOfAnEditorTabNeverShown() {
        saveTabs(toolWindowRecord, editorRecord)
        val (service, _) = newService(FakeFactory(listOf(FakeSession())))
        service.restoreSavedTabs()

        service.prepareForProjectClose()
        service.forgetEditorTabClosedBeforeItWasShown(editorRecord)

        assertEquals(listOf(TOOL_WINDOW_TAB_UUID, EDITOR_TAB_UUID), savedTabs().map { it.tabUuid })
        assertFalse("cleanUp" in strategy.calls)
    }

    fun testAResumedEditorSessionThatEndsRightAwayStartsAgainInTheSameEditorTab() {
        saveTabs(editorRecord)
        val sessions = listOf(FakeSession(), FakeSession())
        val factory = FakeFactory(sessions)
        val (service, host) = newService(factory)
        service.clock = { 1_000_000L }
        val handle = requireNotNull(service.restoreEditorTab(editorRecord))

        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(2, factory.commands.size)
        assertEquals(listOf(handle), host.editorTabs)
        assertTrue(host.tabs.isEmpty())
        assertSame(sessions[1].component, splitterOf(host, handle).firstComponent)
        assertEquals(listOf(true, false), host.disposables.map { it.isDisposed })
        assertEquals(listOf(true), savedTabs().map { it.inEditor })
        assertFalse(EDITOR_TAB_UUID in savedTabs().map { it.tabUuid })
    }

    fun testClosingARestoredEditorTabWhileItLaunchesCleansUpLikeAUserClose() {
        saveTabs(editorRecord)
        val factory = DeferredFactory(FakeSession())
        val (service, host) = newService(factory)
        val handle = requireNotNull(service.restoreEditorTab(editorRecord))

        host.closeEditorTab(handle)
        factory.finish()

        assertTrue(savedTabs().isEmpty())
        assertTrue("cleanUp" in strategy.calls)
        assertNull(host.savedRecordOf(handle))
    }

    fun testARestoredEditorTabReturnedToTheToolWindowWhileItLaunchesOpensThere() {
        saveTabs(editorRecord)
        val factory = DeferredFactory(FakeSession())
        val (service, host) = newService(factory)
        val handle = requireNotNull(service.restoreEditorTab(editorRecord))

        host.returnTabFromEditor(handle)
        factory.finish()

        assertEquals(listOf(handle), host.tabs)
        assertFalse(splitterOf(host, handle).isVertical)
        assertEquals(listOf(EDITOR_TAB_UUID to false), savedTabs().map { it.tabUuid to it.inEditor })
    }
}
