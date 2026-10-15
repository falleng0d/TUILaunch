package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.editor.TuiSessionFile
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.services.TuiOpenTabsService
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TuiSessionEditorTabTest : BasePlatformTestCase() {

    private val services = mutableListOf<TuiAppLaunchService>()

    private class Sessions(
        val service: TuiAppLaunchService,
        val host: FakeHost,
        val editors: FakeEditorHost,
        val sessions: List<FakeSession>,
    )

    override fun setUp() {
        super.setUp()
        resetSettings()
        TuiOpenTabsService.getInstance(project).replaceTabs(emptyList())
    }

    override fun tearDown() {
        try {
            services.forEach { it.releasePromptBoxEditors() }
            resetSettings()
            TuiOpenTabsService.getInstance(project).replaceTabs(emptyList())
        } finally {
            super.tearDown()
        }
    }

    private fun resetSettings() {
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            restoreOpenTabs = false
            promptBoxVisible = false
            rememberPromptBoxVisibilityPerApp = false
            editorPromptBoxBesideTerminal = false
        }
    }

    private fun launch(vararg appNames: String): Sessions {
        TuiLauncherSettings.getInstance().state.tuiApps =
            appNames.mapTo(mutableListOf()) { TuiAppConfig(name = it, command = it) }
        val sessions = appNames.map { FakeSession() }
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        val editors = FakeEditorHost()
        service.host = host
        service.editorHost = editors
        service.sessionFactory = FakeFactory(sessions)
        services.add(service)
        appNames.forEach { service.toggle("TUILauncher.$it", it, it) }
        return Sessions(service, host, editors, sessions)
    }

    private fun Sessions.moveToEditor(index: Int): TuiSessionFile {
        service.moveToEditor(host.tabs[index])
        return editors.openFiles.last()
    }

    private fun savedTitles(): List<String> = TuiOpenTabsService.getInstance(project).state.tabs.map { it.title }

    fun testMovingASessionToTheEditorKeepsItRunningInAnEditorTab() {
        val opened = launch("claude", "codex")
        val handle = opened.host.tabs[0]
        val layout = opened.host.componentOf(handle)

        val file = opened.moveToEditor(0)

        assertEquals(1, opened.host.tabs.size)
        assertFalse(handle in opened.host.tabs)
        assertSame(layout, file.component)
        assertEquals("claude", file.name)
        assertFalse(opened.host.disposables[0].isDisposed)
    }

    fun testASessionDroppedIntoTheEditorSurvivesThePlatformDisposingItsTab() {
        val opened = launch("claude", "codex")

        opened.host.dropTabIntoTheEditor(0, opened.service)
        opened.service.focusTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, opened.editors.openFiles.size)
        assertFalse(opened.host.disposables[0].isDisposed)
    }

    fun testMovingTheLastSessionOutHidesTheEmptyToolWindow() {
        val opened = launch("claude")
        assertTrue(opened.host.visible)

        opened.moveToEditor(0)
        opened.service.toggleToolWindow()

        assertFalse(opened.host.visible)
    }

    fun testSendingGoesToTheSessionThatHadTheFocusLastWhereverItIs() {
        val opened = launch("claude", "codex")
        val (inEditor, inToolWindow) = opened.sessions
        val file = opened.moveToEditor(0)

        opened.service.sendTextToActiveSession("to the editor")
        opened.service.onFocusOwnerChanged(inToolWindow.component)
        opened.service.sendTextToActiveSession("to the tool window")

        assertEquals(listOf("to the editor"), inEditor.sentText)
        assertEquals(listOf("to the tool window"), inToolWindow.sentText)
        assertEquals(listOf(file), opened.editors.selectedFiles)
    }

    fun testSelectingTheEditorTabMakesItsSessionTheActiveOne() {
        val opened = launch("claude", "codex")
        val (inEditor, inToolWindow) = opened.sessions
        val file = opened.moveToEditor(0)
        opened.service.onFocusOwnerChanged(inToolWindow.component)

        opened.editors.userSelects(file)
        opened.service.sendTextToActiveSession("a prompt")

        assertEquals(listOf("a prompt"), inEditor.sentText)
        assertTrue(inToolWindow.sentText.isEmpty())
    }

    fun testTheToolWindowPromptBoxButtonActsOnTheToolWindowSession() {
        TuiLauncherSettings.getInstance().state.rememberPromptBoxVisibilityPerApp = true
        val opened = launch("claude", "codex")
        opened.moveToEditor(0)

        opened.service.setPromptBoxVisible(true, inToolWindow = true)

        assertEquals(true, opened.service.isPromptBoxVisible(inToolWindow = true))
        assertEquals(false, opened.service.isPromptBoxVisible())
    }

    fun testClosingTheEditorTabEndsTheSession() {
        val opened = launch("claude", "codex")
        val file = opened.moveToEditor(0)

        opened.editors.userCloses(file, opened.service)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(opened.host.disposables[0].isDisposed)
        assertFalse(file.isValid)
    }

    fun testMovingTheEditorTabToAnotherSplitKeepsTheSession() {
        val opened = launch("claude")
        val file = opened.moveToEditor(0)

        opened.editors.moveToAnotherSplit(file, opened.service)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(opened.host.disposables[0].isDisposed)
        assertTrue(file.isValid)
    }

    fun testEditorTabsClosedByTheProjectClosingKeepTheirSession() {
        TuiLauncherSettings.getInstance().state.restoreOpenTabs = true
        val opened = launch("claude")
        val file = opened.moveToEditor(0)

        opened.service.projectClosing()
        opened.editors.userCloses(file, opened.service)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(opened.host.disposables[0].isDisposed)
        assertEquals(listOf("claude"), savedTitles())
    }

    fun testCloseActiveTuiClosesTheEditorSessionThatHadTheFocus() {
        val opened = launch("claude", "codex")
        val file = opened.moveToEditor(0)

        opened.service.closeActiveTui()

        assertTrue(opened.host.disposables[0].isDisposed)
        assertFalse(opened.host.disposables[1].isDisposed)
        assertTrue(opened.editors.openFiles.isEmpty())
        assertFalse(file.isValid)
        assertEquals(1, opened.host.tabs.size)
    }

    fun testASessionThatEndsInTheEditorClosesItsTab() {
        val opened = launch("claude")
        val file = opened.moveToEditor(0)

        opened.sessions[0].terminate()

        assertTrue(opened.editors.openFiles.isEmpty())
        assertTrue(opened.host.disposables[0].isDisposed)
        assertFalse(file.isValid)
    }

    fun testMovingBackPutsTheSameSessionAtTheEndOfTheStripAndSelectsIt() {
        val opened = launch("claude", "codex")
        val layout = opened.host.componentOf(opened.host.tabs[0])
        val file = opened.moveToEditor(0)

        opened.service.moveToToolWindow(file)

        assertEquals(2, opened.host.tabs.size)
        val returned = opened.host.tabs.last()
        assertSame(layout, opened.host.componentOf(returned))
        assertSame(returned, opened.host.activeTab())
        assertTrue(opened.editors.openFiles.isEmpty())
        assertFalse(file.isValid)
        assertFalse(opened.host.disposables[0].isDisposed)
    }

    fun testASessionMovedBackEndsWithItsNewToolWindowTab() {
        val opened = launch("claude", "codex")
        val file = opened.moveToEditor(0)
        opened.service.moveToToolWindow(file)

        opened.host.dragTabIntoTheEditor(1)

        assertTrue(opened.host.disposables[0].isDisposed)
        assertFalse(opened.host.disposables[1].isDisposed)
    }

    fun testEditorSessionsAreSavedAfterTheToolWindowTabs() {
        TuiLauncherSettings.getInstance().state.restoreOpenTabs = true
        val opened = launch("claude", "codex")

        opened.moveToEditor(0)

        assertEquals(
            listOf(TuiSessionRecord("codex", "codex", true), TuiSessionRecord("claude", "claude", false)),
            TuiOpenTabsService.getInstance(project).state.tabs.map {
                it.copy(tabUuid = null, agentSessionId = null, agentCliKind = null)
            },
        )
    }

    fun testRenamingAnEditorSessionRenamesItsTabAndRecord() {
        TuiLauncherSettings.getInstance().state.restoreOpenTabs = true
        val opened = launch("claude")
        val file = opened.moveToEditor(0)

        opened.service.renameEditorSession(file, "reviewer")

        assertEquals("reviewer", file.name)
        assertEquals("reviewer", opened.service.sessionTitleOf(file))
        assertEquals(listOf("reviewer"), savedTitles())
    }

    fun testThePromptBoxCanSitBesideTheTerminalOfAnEditorSession() {
        val opened = launch("claude", "codex")
        val file = opened.moveToEditor(0)
        assertEquals(false, opened.service.isPromptBoxBesideTerminal())

        opened.service.setPromptBoxBesideTerminal(true)

        assertEquals(true, opened.service.isPromptBoxBesideTerminal(file))
        assertTrue(TuiLauncherSettings.getInstance().state.editorPromptBoxBesideTerminal)
    }

    fun testTheNextSessionMovedIntoTheEditorKeepsTheChosenSide() {
        val opened = launch("claude", "codex")
        opened.moveToEditor(0)
        opened.service.setPromptBoxBesideTerminal(true)

        val second = opened.moveToEditor(0)

        assertEquals(true, opened.service.isPromptBoxBesideTerminal(second))
    }

    fun testThePromptBoxSideIsOnlyForSessionsInTheEditor() {
        val opened = launch("claude", "codex")
        val file = opened.moveToEditor(0)
        opened.service.onFocusOwnerChanged(opened.sessions[1].component)

        assertNull(opened.service.isPromptBoxBesideTerminal())
        opened.service.setPromptBoxBesideTerminal(true)

        assertEquals(false, opened.service.isPromptBoxBesideTerminal(file))
        assertFalse(TuiLauncherSettings.getInstance().state.editorPromptBoxBesideTerminal)
    }

    fun testTheTerminalShortcutTogglesOnlyAnEditorSessionsPromptBoxSide() {
        val opened = launch("claude", "codex")
        val file = opened.moveToEditor(0)

        assertTrue(opened.service.togglePromptBoxBesideTerminal())
        assertEquals(true, opened.service.isPromptBoxBesideTerminal(file))

        opened.service.onFocusOwnerChanged(opened.sessions[1].component)
        assertFalse(opened.service.togglePromptBoxBesideTerminal())
        assertEquals(true, opened.service.isPromptBoxBesideTerminal(file))
    }
}
