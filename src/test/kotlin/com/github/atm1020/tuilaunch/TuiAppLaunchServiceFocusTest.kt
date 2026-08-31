package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.terminal.TerminalSession
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSize
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSizeAxis
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel

class TuiAppLaunchServiceFocusTest : BasePlatformTestCase() {

    private class FakeSession(private val terminalAcceptsText: Boolean = true) {
        val component: JComponent = JPanel()
        var focusCount = 0
        val sentText = mutableListOf<String>()
        fun requestFocus() {
            focusCount++
        }

        fun asTerminalSession(): TerminalSession = TerminalSession(
            component = component,
            requestFocus = { requestFocus() },
            registerTerminationCallback = {},
            sendText = { text ->
                sentText.add(text)
                terminalAcceptsText
            },
        )
    }

    private class FakeFactory(private val sessions: List<FakeSession>) : TerminalSessionFactory {
        private var index = 0

        constructor(session: FakeSession) : this(listOf(session))

        override fun create(parent: Disposable, command: String): TerminalSession = sessions[index++].asTerminalSession()
    }

    private class DeferredFactory(private val session: FakeSession) : TerminalSessionFactory {
        private var onCreated: ((TerminalSession) -> Unit)? = null
        var createCount = 0

        override fun create(parent: Disposable, command: String): TerminalSession = error("Use async creation")

        override fun createAsync(
            parent: Disposable,
            command: String,
            onCreated: (TerminalSession) -> Unit,
            onFailed: (Throwable) -> Unit,
        ) {
            createCount++
            this.onCreated = onCreated
        }

        fun finish() {
            onCreated?.invoke(session.asTerminalSession())
        }
    }

    private class FakeHost : IdeToolWindowHost(null) {
        var visible = false
        var pinned = true
        var showCount = 0
        private var selected: Any? = null
        val tabs = mutableListOf<Any>()
        val titles = mutableListOf<String>()
        val disposables = mutableListOf<CheckedDisposable>()
        var size: ToolWindowSize? = null
        var axis = ToolWindowSizeAxis.HEIGHT
        val appliedSizes = mutableListOf<ToolWindowSize>()
        var emitStaleResizeOnApply = false
        private var sizeChanged: (() -> Unit)? = null
        private var tabSelected: ((Any) -> Unit)? = null
        private var tabAdded: ((Any) -> Unit)? = null
        private var tabRemoving: ((Any) -> Unit)? = null
        private var tabRemoved: ((Any) -> Unit)? = null
        private val tabsRemovedForDrag = mutableSetOf<Any>()
        private val disposableByTab = mutableMapOf<Any, CheckedDisposable>()

        override fun isVisible(): Boolean = visible
        override fun isPinned(): Boolean = pinned
        override fun show() {
            visible = true
            showCount++
        }

        override fun hide() {
            visible = false
        }

        override fun addTab(component: JComponent, title: String, disposable: Disposable): Any {
            val handle = Any()
            tabs.add(handle)
            titles.add(title)
            (disposable as? CheckedDisposable)?.let {
                disposables.add(it)
                disposableByTab[handle] = it
            }
            tabAdded?.invoke(handle)
            if (selected == null) {
                selected = handle
                tabSelected?.invoke(handle)
            }
            return handle
        }

        override fun selectTab(handle: Any) {
            selected = handle
            tabSelected?.invoke(handle)
        }

        override fun activeTab(): Any? = selected

        override fun orderedHandles(): List<Any> = tabs.toList()

        override fun removeTab(handle: Any) {
            val index = tabs.indexOf(handle)
            if (index < 0) return
            tabRemoving?.invoke(handle)
            val removingSelectedTab = selected === handle
            if (removingSelectedTab) selected = null
            tabs.removeAt(index)
            tabRemoved?.invoke(handle)
            if (removingSelectedTab) {
                selected = tabs.getOrNull(index) ?: tabs.getOrNull(index - 1)
                selected?.let { tabSelected?.invoke(it) }
            }
        }

        fun dragTab(from: Int, to: Int) {
            val handle = detachTabForDrag(from)
            tabs.add(to, handle)
            tabAdded?.invoke(handle)
            selectTab(handle)
            tabsRemovedForDrag.remove(handle)
        }

        fun dragTabOutOfTheStrip(from: Int) {
            tabsRemovedForDrag.remove(detachTabForDrag(from))
        }

        fun dragTabIntoTheEditor(from: Int) {
            val handle = detachTabForDrag(from)
            disposableByTab[handle]?.let { Disposer.dispose(it) }
            tabsRemovedForDrag.remove(handle)
        }

        private fun detachTabForDrag(from: Int): Any {
            val handle = tabs[from]
            tabsRemovedForDrag.add(handle)
            if (selected === handle) selected = tabs.getOrNull(from + 1) ?: tabs.getOrNull(from - 1)
            tabs.removeAt(from)
            tabRemoved?.invoke(handle)
            selected?.let { tabSelected?.invoke(it) }
            return handle
        }

        fun visiblePositionOfActiveTab(): Int = tabs.indexOf(selected) + 1

        override fun isTabRemovedForDrag(handle: Any): Boolean = handle in tabsRemovedForDrag

        override fun isTabAttachedToToolWindow(handle: Any): Boolean = handle in tabs

        override fun currentSize(): ToolWindowSize? = size

        override fun sizeAxis(): ToolWindowSizeAxis = axis

        override fun applySize(size: ToolWindowSize) {
            appliedSizes.add(size)
            if (emitStaleResizeOnApply) {
                sizeChanged?.invoke()
            }
            this.size = size
        }

        override fun onSizeChanged(listener: () -> Unit) {
            sizeChanged = listener
        }

        override fun onTabSelected(listener: (Any) -> Unit) {
            tabSelected = listener
        }

        override fun onTabAdded(listener: (Any) -> Unit) {
            tabAdded = listener
        }

        override fun onTabRemoved(beforeRemoval: (Any) -> Unit, afterRemoval: (Any) -> Unit) {
            tabRemoving = beforeRemoval
            tabRemoved = afterRemoval
        }

        fun triggerSizeChanged() {
            sizeChanged?.invoke()
        }

        fun triggerTabSelected(handle: Any) {
            tabSelected?.invoke(handle)
        }

        fun triggerTabRemoved(handle: Any) {
            tabRemoved?.invoke(handle)
        }
    }

    private class AnchoredToolWindow(
        project: Project,
        private val toolWindowAnchor: ToolWindowAnchor,
        private val toolWindowType: ToolWindowType,
    ) : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
        override fun getAnchor(): ToolWindowAnchor = toolWindowAnchor
        override fun getType(): ToolWindowType = toolWindowType
    }

    private fun sizeAxisOf(
        anchor: ToolWindowAnchor,
        type: ToolWindowType = ToolWindowType.DOCKED,
    ): ToolWindowSizeAxis = IdeToolWindowHost(AnchoredToolWindow(project, anchor, type)).sizeAxis()

    private fun newService(
        sessionFactory: TerminalSessionFactory = FakeFactory(emptyList()),
    ): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project)
        val host = FakeHost()
        service.host = host
        service.sessionFactory = sessionFactory
        return service to host
    }

    private fun launchTabs(count: Int): Pair<TuiAppLaunchService, FakeHost> {
        val (service, host) = newService(FakeFactory(List(count + 2) { FakeSession() }))
        repeat(count) { service.launchNew("claude", "claude") }
        return service to host
    }

    private fun configureApps(vararg apps: TuiAppConfig) {
        TuiLauncherSettings.getInstance().state.tuiApps = apps.toMutableList()
    }

    fun testSendTextReportsFailureWhenNoSessionIsOpen() {
        val (service, host) = newService()

        assertFalse(service.sendTextToActiveSession("a prompt"))
        assertFalse(host.visible)
    }

    fun testSendTextReportsFailureWhenTheTerminalCannotAcceptIt() {
        val session = FakeSession(terminalAcceptsText = false)
        val (service, _) = newService(FakeFactory(session))
        service.launchNew("claude", "claude")

        assertFalse(service.sendTextToActiveSession("a prompt"))
        assertEquals(listOf("a prompt"), session.sentText)
    }

    fun testSendTextGoesToTheActiveSessionAndFocusesTheToolWindow() {
        val session = FakeSession()
        val (service, host) = newService(FakeFactory(session))
        service.launchNew("claude", "claude")
        val focusCountBeforeSend = session.focusCount

        assertTrue(service.sendTextToActiveSession("a prompt"))

        assertEquals(listOf("a prompt"), session.sentText)
        assertTrue(host.visible)
        assertTrue(session.focusCount > focusCountBeforeSend)
    }

    fun testFocusTuiDoesNothingWhenNoAppOpen() {
        val (service, host) = newService()

        service.focusTui()

        assertEquals(0, host.showCount)
        assertFalse(host.visible)
    }

    fun testResizeEventStoresActiveTabSize() {
        configureApps(TuiAppConfig(name = "htop", command = "htop"))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.size = ToolWindowSize(900, 500)
        host.triggerSizeChanged()

        val app = TuiLauncherSettings.getInstance().state.tuiApps.single()
        assertEquals(900, app.windowWidth)
        assertEquals(500, app.windowHeight)
    }

    fun testSelectingTabAppliesItsSavedSize() {
        configureApps(
            TuiAppConfig(name = "first", command = "first", windowWidth = 700, windowHeight = 400),
            TuiAppConfig(name = "second", command = "second", windowWidth = 1100, windowHeight = 800),
        )
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        val firstTab = host.tabs[0]
        host.appliedSizes.clear()

        host.triggerTabSelected(firstTab)

        assertEquals(listOf(ToolWindowSize(700, 400)), host.appliedSizes)
    }

    fun testSelectingTabKeepsSavedSizeWhenApplyTriggersResizeEvent() {
        configureApps(
            TuiAppConfig(name = "first", command = "first", windowWidth = 700, windowHeight = 400),
            TuiAppConfig(name = "second", command = "second", windowWidth = 1100, windowHeight = 800),
        )
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))
        host.emitStaleResizeOnApply = true
        host.size = ToolWindowSize(700, 400)

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")

        host.size = ToolWindowSize(1100, 800)
        host.selectTab(host.tabs[0])

        val first = TuiLauncherSettings.getInstance().state.tuiApps.first { it.name == "first" }
        assertEquals(700, first.windowWidth)
        assertEquals(400, first.windowHeight)
    }

    fun testTogglingToOpenTabAppliesSavedSizeExactlyOnce() {
        configureApps(
            TuiAppConfig(name = "first", command = "first", windowWidth = 700, windowHeight = 400),
            TuiAppConfig(name = "second", command = "second", windowWidth = 1100, windowHeight = 800),
        )
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        host.appliedSizes.clear()

        service.toggle("TUILauncher.first", "first", "first")

        assertEquals(listOf(ToolWindowSize(700, 400)), host.appliedSizes)
    }

    fun testFirstLaunchAppliesSavedSize() {
        configureApps(TuiAppConfig(name = "htop", command = "htop", windowWidth = 900, windowHeight = 600))
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.size = ToolWindowSize(500, 300)

        service.toggle("TUILauncher.htop", "htop", "htop")

        assertEquals(ToolWindowSize(900, 600), host.size)
    }

    fun testTogglingTabAppliesSavedSizeViaSelection() {
        configureApps(TuiAppConfig(name = "htop", command = "htop", windowWidth = 900, windowHeight = 600))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")

        assertEquals(ToolWindowSize(900, 600), host.appliedSizes.last())
    }

    fun testSelectingTabWithoutSavedSizeAppliesNothing() {
        configureApps(TuiAppConfig(name = "htop", command = "htop"))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.appliedSizes.clear()

        host.triggerTabSelected(host.tabs[0])

        assertTrue(host.appliedSizes.isEmpty())
    }

    fun testARightAnchoredToolWindowIsResizedAlongItsWidth() {
        assertEquals(ToolWindowSizeAxis.WIDTH, sizeAxisOf(ToolWindowAnchor.RIGHT))
    }

    fun testALeftAnchoredToolWindowIsResizedAlongItsWidth() {
        assertEquals(ToolWindowSizeAxis.WIDTH, sizeAxisOf(ToolWindowAnchor.LEFT))
    }

    fun testABottomAnchoredToolWindowIsResizedAlongItsHeight() {
        assertEquals(ToolWindowSizeAxis.HEIGHT, sizeAxisOf(ToolWindowAnchor.BOTTOM))
    }

    fun testATopAnchoredToolWindowIsResizedAlongItsHeight() {
        assertEquals(ToolWindowSizeAxis.HEIGHT, sizeAxisOf(ToolWindowAnchor.TOP))
    }

    fun testAFloatingToolWindowIsResizedAlongBothAxes() {
        assertEquals(ToolWindowSizeAxis.BOTH, sizeAxisOf(ToolWindowAnchor.RIGHT, ToolWindowType.FLOATING))
    }

    fun testAWindowedToolWindowIsResizedAlongBothAxes() {
        assertEquals(ToolWindowSizeAxis.BOTH, sizeAxisOf(ToolWindowAnchor.BOTTOM, ToolWindowType.WINDOWED))
    }

    fun testResizeRecordsTheAxisTheWindowWasResizedAlong() {
        configureApps(TuiAppConfig(name = "htop", command = "htop"))
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.axis = ToolWindowSizeAxis.WIDTH

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.size = ToolWindowSize(620, 900)
        host.triggerSizeChanged()

        val app = TuiLauncherSettings.getInstance().state.tuiApps.single()
        assertEquals(620, app.windowWidth)
        assertEquals(900, app.windowHeight)
        assertEquals("WIDTH", app.windowSizeAxis)
    }

    fun testResizeAtTheBottomRecordsTheHeightAxis() {
        configureApps(TuiAppConfig(name = "htop", command = "htop"))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.size = ToolWindowSize(1800, 500)
        host.triggerSizeChanged()

        assertEquals("HEIGHT", TuiLauncherSettings.getInstance().state.tuiApps.single().windowSizeAxis)
    }

    fun testASideDockedWindowIgnoresASizeRecordedAtTheBottom() {
        configureApps(
            TuiAppConfig(
                name = "htop",
                command = "htop",
                windowWidth = 1800,
                windowHeight = 500,
                windowSizeAxis = "HEIGHT",
            )
        )
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.axis = ToolWindowSizeAxis.WIDTH

        service.toggle("TUILauncher.htop", "htop", "htop")

        assertTrue(host.appliedSizes.isEmpty())
    }

    fun testASideDockedWindowIgnoresASizeRecordedBeforeTheAxisWasTracked() {
        configureApps(TuiAppConfig(name = "htop", command = "htop", windowWidth = 1800, windowHeight = 500))
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.axis = ToolWindowSizeAxis.WIDTH

        service.toggle("TUILauncher.htop", "htop", "htop")

        assertTrue(host.appliedSizes.isEmpty())
    }

    fun testABottomDockedWindowStillRestoresASizeRecordedBeforeTheAxisWasTracked() {
        configureApps(TuiAppConfig(name = "htop", command = "htop", windowWidth = 1800, windowHeight = 500))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")

        assertEquals(listOf(ToolWindowSize(1800, 500)), host.appliedSizes.distinct())
    }

    fun testASideDockedWindowRestoresASizeRecordedWhileSideDocked() {
        configureApps(
            TuiAppConfig(
                name = "htop",
                command = "htop",
                windowWidth = 620,
                windowHeight = 900,
                windowSizeAxis = "WIDTH",
            )
        )
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.axis = ToolWindowSizeAxis.WIDTH

        service.toggle("TUILauncher.htop", "htop", "htop")

        assertEquals(listOf(ToolWindowSize(620, 900)), host.appliedSizes.distinct())
    }

    fun testABottomDockedWindowIgnoresASizeRecordedWhileSideDocked() {
        configureApps(
            TuiAppConfig(
                name = "htop",
                command = "htop",
                windowWidth = 620,
                windowHeight = 900,
                windowSizeAxis = "WIDTH",
            )
        )
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")

        assertTrue(host.appliedSizes.isEmpty())
    }

    fun testASizeRecordedOnASideDockIsRestoredOnTheNextLaunchThere() {
        configureApps(TuiAppConfig(name = "htop", command = "htop", windowWidth = 1800, windowHeight = 500))
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.axis = ToolWindowSizeAxis.WIDTH

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.size = ToolWindowSize(620, 900)
        host.triggerSizeChanged()
        host.appliedSizes.clear()

        host.triggerTabSelected(host.tabs.single())

        assertEquals(listOf(ToolWindowSize(620, 900)), host.appliedSizes)
    }

    fun testFocusTuiRevealsAndFocusesActiveTab() {
        val session = FakeSession()
        val (service, host) = newService(FakeFactory(session))

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.showCount = 0
        session.focusCount = 0

        service.focusTui()

        assertTrue(host.visible)
        assertEquals(1, host.showCount)
        assertEquals(1, session.focusCount)
        assertNotNull(host.activeTab())
    }

    fun testToggleWaitsForAsynchronousSessionCreation() {
        val session = FakeSession()
        val factory = DeferredFactory(session)
        val (service, host) = newService(factory)

        service.toggle("TUILauncher.htop", "htop", "htop")
        service.toggle("TUILauncher.htop", "htop", "htop")

        assertEquals(1, factory.createCount)
        assertTrue(host.tabs.isEmpty())

        factory.finish()

        assertEquals(1, host.tabs.size)
        assertSame(host.tabs.single(), host.activeTab())
        assertEquals(1, session.focusCount)
    }

    fun testToggleToolWindowShowsHiddenWindowWithoutRequestingFocus() {
        val session = FakeSession()
        val (service, host) = newService(FakeFactory(session))

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.hide()
        host.showCount = 0
        session.focusCount = 0

        service.toggleToolWindow()

        assertTrue(host.visible)
        assertEquals(1, host.showCount)
        assertEquals(0, session.focusCount)
    }

    fun testToggleToolWindowHidesVisibleWindow() {
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")

        service.toggleToolWindow()

        assertFalse(host.visible)
    }

    fun testCloseActiveTuiRemovesSelectedTab() {
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        val firstTab = host.activeTab()
        service.toggle("TUILauncher.second", "second", "second")

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf(firstTab), host.tabs)
        assertTrue(host.visible)
    }

    fun testCloseActiveTuiHidesWindowAfterLastTab() {
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")
        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.tabs.isEmpty())
        assertFalse(host.visible)
    }

    fun testCloseLastTuiKeepsWindowVisibleWhenAlreadyVisibleBeforeLaunch() {
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.visible = true

        service.toggle("TUILauncher.htop", "htop", "htop")
        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.tabs.isEmpty())
        assertTrue(host.visible)
    }

    fun testCloseLastTuiKeepsWindowVisibleWithMultipleTabsWhenVisibleBeforeLaunch() {
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))
        host.visible = true

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        service.closeActiveTui()
        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.tabs.isEmpty())
        assertTrue(host.visible)
    }

    fun testCloseLastTuiKeepsWindowVisibleWhenUnpinned() {
        val (service, host) = newService(FakeFactory(FakeSession()))
        host.pinned = false

        service.toggle("TUILauncher.htop", "htop", "htop")
        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.tabs.isEmpty())
        assertTrue(host.visible)
    }

    fun testCloseActiveTuiOpenedFromEditorReturnsFocusToEditor() {
        val (service, host) = newService(FakeFactory(FakeSession()))
        service.activeToolWindowIdProvider = { "Project" }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        service.toggle("TUILauncher.htop", "htop", "htop")
        service.closeActiveTui()

        assertEquals(1, editorFocusCount)
    }

    fun testCloseActiveTuiOpenedFromTuiDoesNotReturnFocusToEditor() {
        val (service, host) = newService(FakeFactory(FakeSession()))
        service.activeToolWindowIdProvider = { TUI_TOOL_WINDOW_ID }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        service.toggle("TUILauncher.htop", "htop", "htop")
        service.closeActiveTui()

        assertEquals(0, editorFocusCount)
    }

    fun testNextTuiTabSelectsAndFocusesNextTab() {
        val firstSession = FakeSession()
        val secondSession = FakeSession()
        val (service, host) = newService(FakeFactory(listOf(firstSession, secondSession)))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        host.selectTab(host.tabs[0])
        secondSession.focusCount = 0

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(host.tabs[1], host.activeTab())
        assertEquals(1, secondSession.focusCount)
    }

    fun testNextTuiTabWithoutFocusSelectsWithoutRequestingFocus() {
        val firstSession = FakeSession()
        val secondSession = FakeSession()
        val (service, host) = newService(FakeFactory(listOf(firstSession, secondSession)))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        host.selectTab(host.tabs[0])
        secondSession.focusCount = 0

        service.nextTuiTabWithoutFocus()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(host.tabs[1], host.activeTab())
        assertEquals(0, secondSession.focusCount)
    }

    fun testPreviousTuiTabWithoutFocusSelectsWithoutRequestingFocus() {
        val firstSession = FakeSession()
        val secondSession = FakeSession()
        val (service, host) = newService(FakeFactory(listOf(firstSession, secondSession)))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        firstSession.focusCount = 0

        service.previousTuiTabWithoutFocus()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(host.tabs[0], host.activeTab())
        assertEquals(0, firstSession.focusCount)
    }

    fun testPreviousTuiTabSelectsAndFocusesPreviousTab() {
        val firstSession = FakeSession()
        val secondSession = FakeSession()
        val (service, host) = newService(FakeFactory(listOf(firstSession, secondSession)))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        firstSession.focusCount = 0

        service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(host.tabs[0], host.activeTab())
        assertEquals(1, firstSession.focusCount)
    }

    fun testPrefixCommandActionsLaunchConfiguredApps() {
        configureApps(TuiAppConfig(name = "lazygit", command = "lazygit", shortcutKeyCode = KeyEvent.VK_G))

        val (service, host) = newService(FakeFactory(FakeSession()))

        service.prefixCommandActions().getValue(KeyEvent.VK_G).invoke()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, host.tabs.size)
        assertSame(host.tabs.single(), host.activeTab())
    }

    fun testPrefixCommandActionsUseConfiguredKeys() {
        val state = TuiLauncherSettings.getInstance().state
        state.closeTuiKeyCode = KeyEvent.VK_X
        state.nextTuiKeyCode = KeyEvent.VK_Y
        state.previousTuiKeyCode = KeyEvent.VK_Z

        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        val actions = service.prefixCommandActions()

        assertFalse(actions.containsKey(KeyEvent.VK_C))
        assertTrue(actions.containsKey(KeyEvent.VK_X))

        actions.getValue(KeyEvent.VK_X).invoke()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, host.tabs.size)
    }

    fun testLaunchNewOpensASecondInstanceOfTheSameAppWithANumberedTitle() {
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.launchNew("claude", "claude")
        val firstHandle = host.activeTab()
        service.launchNew("claude", "claude")
        val secondHandle = host.activeTab()

        assertEquals(2, host.tabs.size)
        assertNotSame(firstHandle, secondHandle)
        assertEquals(listOf("claude", "claude 1"), host.titles)
    }

    fun testNextAndPreviousTuiTabCycleThroughMultipleInstancesOfTheSameApp() {
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.launchNew("claude", "claude")
        service.launchNew("claude", "claude")
        host.selectTab(host.tabs[0])

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(host.tabs[1], host.activeTab())

        service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(host.tabs[0], host.activeTab())
    }

    fun testClosingOneInstanceLeavesTheOtherOpenAndSelectable() {
        val firstSession = FakeSession()
        val secondSession = FakeSession()
        val (service, host) = newService(FakeFactory(listOf(firstSession, secondSession)))

        service.launchNew("claude", "claude")
        val remaining = host.activeTab()
        service.launchNew("claude", "claude")

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf(remaining), host.tabs)

        firstSession.focusCount = 0
        service.focusTui()

        assertSame(remaining, host.activeTab())
        assertEquals(1, firstSession.focusCount)
    }

    fun testRenameTabUpdatesTitleAndLaunchNewNumbersAroundIt() {
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession(), FakeSession())))

        service.launchNew("claude", "claude")
        service.launchNew("helper", "helper")
        val helperHandle = host.tabs[1]

        service.renameTab(helperHandle, "claude 1")
        service.launchNew("claude", "claude")

        assertEquals(listOf("claude", "helper", "claude 2"), host.titles)
    }

    fun testNextTuiTabWalksTheStripLeftToRightAndWrapsPastTheLastTab() {
        val (service, host) = launchTabs(3)
        val (first, second, third) = Triple(host.tabs[0], host.tabs[1], host.tabs[2])
        host.selectTab(first)

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(second, host.activeTab())

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(third, host.activeTab())

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(first, host.activeTab())
    }

    fun testPreviousTuiTabWalksTheStripRightToLeftAndWrapsPastTheFirstTab() {
        val (service, host) = launchTabs(3)
        val (first, second, third) = Triple(host.tabs[0], host.tabs[1], host.tabs[2])
        host.selectTab(first)

        service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(third, host.activeTab())

        service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(second, host.activeTab())

        service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(first, host.activeTab())
    }

    fun testWrappingBackwardThenGoingForwardStaysInStripOrder() {
        val (service, host) = launchTabs(3)
        val (first, second, third) = Triple(host.tabs[0], host.tabs[1], host.tabs[2])
        host.selectTab(first)

        service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(third, host.activeTab())

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(first, host.activeTab())

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(second, host.activeTab())
    }

    fun testTwoNextPressesInOneEventTurnAdvanceTwoTabs() {
        val (service, host) = launchTabs(3)
        val (first, _, third) = Triple(host.tabs[0], host.tabs[1], host.tabs[2])
        host.selectTab(first)

        service.nextTuiTab()
        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(third, host.activeTab())
    }

    fun testNextTabWalksVisiblePositionsAfterATabIsDraggedToTheFront() {
        val (service, host) = launchTabs(4)

        host.dragTab(2, 0)

        assertEquals(1, host.visiblePositionOfActiveTab())

        val visitedPositions = (1..3).map {
            service.nextTuiTab()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            host.visiblePositionOfActiveTab()
        }

        assertEquals(listOf(2, 3, 4), visitedPositions)
    }

    fun testPreviousTabWalksVisiblePositionsBackwardsAfterATabIsDraggedToTheEnd() {
        val (service, host) = launchTabs(4)

        host.dragTab(0, 3)

        assertEquals(4, host.visiblePositionOfActiveTab())

        val visitedPositions = (1..4).map {
            service.previousTuiTab()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            host.visiblePositionOfActiveTab()
        }

        assertEquals(listOf(3, 2, 1, 4), visitedPositions)
    }

    fun testDraggingATabKeepsEverySessionAlive() {
        val (_, host) = launchTabs(3)

        host.dragTab(0, 2)

        assertTrue(host.disposables.none { it.isDisposed })
    }

    fun testATabDraggedOutOfTheStripAndNeverDroppedBackIsClosed() {
        val (service, host) = launchTabs(2)
        val remaining = host.tabs[1]

        host.dragTabOutOfTheStrip(0)
        assertFalse(host.disposables[0].isDisposed)

        service.focusTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.disposables[0].isDisposed)
        assertFalse(host.disposables[1].isDisposed)
        assertEquals(listOf(remaining), host.tabs)
    }

    fun testASessionDestroyedByTheEditorDropIsForgotten() {
        val (service, host) = launchTabs(2)

        host.dragTabIntoTheEditor(0)

        service.launchNew("claude", "claude")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf("claude", "claude 1", "claude"), host.titles)
        assertFalse(host.disposables[1].isDisposed)
    }

    fun testTraversalImmediatelyAfterACloseUsesTheRemainingStrip() {
        val (service, host) = launchTabs(4)
        val tabs = host.tabs.toList()
        host.selectTab(tabs[1])

        service.closeActiveTui()
        service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf(tabs[0], tabs[2], tabs[3]), host.tabs)
        assertSame(tabs[0], host.activeTab())
    }

    fun testPlatformInitiatedRemovalForgetsTheSession() {
        val (service, host) = launchTabs(2)
        val first = host.tabs[0]

        host.removeTab(host.tabs[1])

        assertEquals(listOf(first), host.tabs)
        service.launchNew("claude", "claude")
        assertEquals("claude 1", host.titles.last())
    }

    fun testPlatformInitiatedRemovalDisposesOnlyThatSession() {
        val (_, host) = launchTabs(2)

        host.removeTab(host.tabs[1])

        assertTrue(host.disposables[1].isDisposed)
        assertFalse(host.disposables[0].isDisposed)
    }

    fun testClosingEveryTabFromThePlatformLeavesNoSessionsBehind() {
        val (service, host) = launchTabs(3)

        host.tabs.toList().forEach { host.removeTab(it) }

        assertTrue(host.tabs.isEmpty())
        assertTrue(host.disposables.all { it.isDisposed })
        host.showCount = 0
        service.focusTui()
        assertEquals(0, host.showCount)
    }

    fun testReconcilingAnAlreadyClosedTabIsANoOp() {
        val (service, host) = launchTabs(2)
        val first = host.tabs[0]
        val second = host.tabs[1]
        host.selectTab(second)

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(first), host.tabs)

        host.triggerTabRemoved(second)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf(first), host.tabs)
        assertFalse(host.disposables[0].isDisposed)
        service.focusTui()
        assertSame(first, host.activeTab())
    }

    fun testPlatformInitiatedRemovalRecordsTheClosedTabSize() {
        configureApps(TuiAppConfig(name = "htop", command = "htop"))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.size = ToolWindowSize(940, 520)

        host.removeTab(host.tabs.single())

        val app = TuiLauncherSettings.getInstance().state.tuiApps.single()
        assertEquals(940, app.windowWidth)
        assertEquals(520, app.windowHeight)
    }

    fun testPlatformInitiatedRemovalRecordsTheClosedTabSizeWhenOtherTabsRemain() {
        configureApps(
            TuiAppConfig(name = "first", command = "first"),
            TuiAppConfig(name = "second", command = "second"),
        )
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        host.size = ToolWindowSize(1180, 640)

        host.removeTab(host.tabs[1])

        val second = TuiLauncherSettings.getInstance().state.tuiApps.first { it.name == "second" }
        assertEquals(1180, second.windowWidth)
        assertEquals(640, second.windowHeight)
    }

    fun testPlatformInitiatedRemovalOfABackgroundTabDoesNotRecordTheWindowSizeForIt() {
        configureApps(
            TuiAppConfig(name = "first", command = "first"),
            TuiAppConfig(name = "second", command = "second"),
        )
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        host.size = ToolWindowSize(1180, 640)

        host.removeTab(host.tabs[0])

        val first = TuiLauncherSettings.getInstance().state.tuiApps.first { it.name == "first" }
        assertNull(first.windowWidth)
        assertNull(first.windowHeight)
    }

    fun testPlatformInitiatedRemovalKeepsTheSavedSizeWhenTheWindowSizeIsUnreadable() {
        configureApps(TuiAppConfig(name = "htop", command = "htop", windowWidth = 800, windowHeight = 450))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.size = null

        host.removeTab(host.tabs.single())

        val app = TuiLauncherSettings.getInstance().state.tuiApps.single()
        assertEquals(800, app.windowWidth)
        assertEquals(450, app.windowHeight)
    }

    fun testPlatformInitiatedRemovalOfTheLastTabReturnsFocusToEditor() {
        val (service, host) = newService(FakeFactory(FakeSession()))
        service.activeToolWindowIdProvider = { "Project" }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.removeTab(host.tabs.single())

        assertEquals(1, editorFocusCount)
    }

    fun testPlatformInitiatedRemovalKeepsFocusInTheToolWindowWhenOtherTabsRemain() {
        val (service, host) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))
        service.activeToolWindowIdProvider = { "Project" }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")
        host.removeTab(host.tabs[1])

        assertEquals(0, editorFocusCount)
        assertSame(host.tabs.single(), host.activeTab())
    }

    fun testPlatformInitiatedRemovalOfTheLastTabOpenedFromTuiKeepsFocusOutOfTheEditor() {
        val (service, host) = newService(FakeFactory(FakeSession()))
        service.activeToolWindowIdProvider = { TUI_TOOL_WINDOW_ID }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.removeTab(host.tabs.single())

        assertEquals(0, editorFocusCount)
    }

    fun testClosingEveryTabFromThePlatformReturnsFocusToTheEditorOnlyOnce() {
        val (service, host) = newService(FakeFactory(List(3) { FakeSession() }))
        service.activeToolWindowIdProvider = { "Project" }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        repeat(3) { service.launchNew("claude", "claude") }
        host.tabs.toList().forEach { host.removeTab(it) }

        assertTrue(host.tabs.isEmpty())
        assertEquals(1, editorFocusCount)
    }

    fun testClosingThroughOurOwnActionRecordsSizeAndReturnsFocusExactlyOnce() {
        configureApps(TuiAppConfig(name = "htop", command = "htop"))
        val (service, host) = newService(FakeFactory(FakeSession()))
        service.activeToolWindowIdProvider = { "Project" }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        service.toggle("TUILauncher.htop", "htop", "htop")
        host.size = ToolWindowSize(960, 540)
        service.closeActiveTui()
        host.size = ToolWindowSize(111, 222)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val app = TuiLauncherSettings.getInstance().state.tuiApps.single()
        assertEquals(960, app.windowWidth)
        assertEquals(540, app.windowHeight)
        assertEquals(1, editorFocusCount)
        assertTrue(host.tabs.isEmpty())
    }

    fun testReplayingARemovalEventAfterOurOwnCloseDoesNotReturnFocusAgain() {
        val (service, host) = newService(FakeFactory(FakeSession()))
        service.activeToolWindowIdProvider = { "Project" }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }

        service.toggle("TUILauncher.htop", "htop", "htop")
        val handle = host.tabs.single()
        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        host.triggerTabRemoved(handle)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, editorFocusCount)
    }
}
