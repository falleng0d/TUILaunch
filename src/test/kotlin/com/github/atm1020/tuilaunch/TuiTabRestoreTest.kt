package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.services.TuiOpenTabsService
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TuiTabRestoreTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            restoreOpenTabs = true
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

    private fun newService(
        sessionFactory: TerminalSessionFactory = FakeFactory(emptyList()),
    ): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project)
        val host = FakeHost()
        service.host = host
        service.sessionFactory = sessionFactory
        return service to host
    }

    private fun configureApps(vararg names: String) {
        TuiLauncherSettings.getInstance().state.tuiApps =
            names.mapTo(mutableListOf()) { TuiAppConfig(name = it, command = it) }
    }

    private fun disableRestore() {
        TuiLauncherSettings.getInstance().state.restoreOpenTabs = false
    }

    private fun savedTabs(): List<TuiSessionRecord> = TuiOpenTabsService.getInstance(project).state.tabs

    private fun saveTabs(vararg records: TuiSessionRecord) {
        TuiOpenTabsService.getInstance(project).replaceTabs(records.toList())
    }

    private fun savedTitles(): List<String> = savedTabs().map { it.title }

    private fun savedAppNames(): List<String> = savedTabs().map { it.appName }

    private fun selectedTitle(): String? = savedTabs().singleOrNull { it.selected }?.title

    private fun launchTabs(count: Int): Pair<TuiAppLaunchService, FakeHost> {
        val (service, host) = newService(FakeFactory(List(count) { FakeSession() }))
        repeat(count) { service.launchNew("claude", "claude") }
        return service to host
    }

    fun testLaunchingTwoAppsRecordsThemInStripOrderWithTheLastSelected() {
        configureApps("first", "second")
        val (service, _) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")

        assertEquals(
            listOf(
                TuiSessionRecord("first", "first", false),
                TuiSessionRecord("second", "second", true),
            ),
            savedTabs(),
        )
    }

    fun testTheFirstTabIsRecordedEvenThoughItIsSelectedByTheAddItself() {
        configureApps("first")
        val (service, _) = newService(FakeFactory(FakeSession()))

        service.toggle("TUILauncher.first", "first", "first")

        assertEquals(listOf(TuiSessionRecord("first", "first", true)), savedTabs())
    }

    fun testNothingIsRecordedWhileTheFeatureIsOff() {
        disableRestore()
        configureApps("first", "second")
        val (service, _) = newService(FakeFactory(listOf(FakeSession(), FakeSession())))

        service.toggle("TUILauncher.first", "first", "first")
        service.toggle("TUILauncher.second", "second", "second")

        assertTrue(savedTabs().isEmpty())
    }

    fun testClosingATabDropsItsRecordAndKeepsTheSurvivorsInPlace() {
        val (service, _) = launchTabs(3)

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf("claude", "claude 1"), savedTitles())
        assertEquals("claude 1", selectedTitle())
    }

    fun testClosingEveryTabLeavesNoRecords() {
        val (_, host) = launchTabs(3)

        host.tabs.toList().forEach { host.removeTab(it) }

        assertTrue(savedTabs().isEmpty())
    }

    fun testRenamingATabRecordsTheNewTitleAndKeepsTheAppName() {
        val (service, host) = launchTabs(2)

        service.renameTab(host.tabs[0], "agent")

        assertEquals(listOf("agent", "claude 1"), savedTitles())
        assertEquals(listOf("claude", "claude"), savedAppNames())
    }

    fun testDraggingATabToTheFrontReordersTheRecords() {
        val (_, host) = launchTabs(3)

        host.dragTab(2, 0)

        assertEquals(listOf("claude 2", "claude", "claude 1"), savedTitles())
        assertEquals("claude 2", selectedTitle())
    }

    fun testATabDraggedOutOfTheStripAndAbandonedDropsOutOfTheRecords() {
        val (service, host) = launchTabs(1)

        host.dragTabOutOfTheStrip(0)
        assertEquals(listOf("claude"), savedTitles())

        service.focusTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(savedTabs().isEmpty())
    }

    fun testSelectingADifferentTabMovesTheSelectedFlag() {
        val (_, host) = launchTabs(3)

        host.selectTab(host.tabs[0])

        assertEquals("claude", selectedTitle())
        assertEquals(1, savedTabs().count { it.selected })
    }

    fun testTwoSessionsOfTheSameAppAreRecordedWithTheirNumberedTitles() {
        launchTabs(2)

        assertEquals(listOf("claude", "claude 1"), savedTitles())
        assertEquals(listOf("claude", "claude"), savedAppNames())
    }

    fun testRestoreOpensTheSavedTabsInTheSavedOrder() {
        configureApps("first", "second", "third")
        saveTabs(
            TuiSessionRecord("first", "first"),
            TuiSessionRecord("second", "second"),
            TuiSessionRecord("third", "third"),
        )
        val (service, host) = newService(FakeFactory(List(3) { FakeSession() }))

        service.restoreSavedTabs()

        assertEquals(listOf("first", "second", "third"), host.titles)
    }

    fun testRestoreKeepsTheSavedOrderWhenLaunchesCompleteOutOfOrder() {
        configureApps("first", "second", "third")
        saveTabs(
            TuiSessionRecord("first", "first"),
            TuiSessionRecord("second", "second"),
            TuiSessionRecord("third", "third"),
        )
        val factory = DeferredFactory(List(3) { FakeSession() })
        val (service, host) = newService(factory)

        service.restoreSavedTabs()
        assertEquals(1, factory.createCount)
        assertTrue(host.titles.isEmpty())

        factory.finish()
        assertEquals(listOf("first"), host.titles)
        assertEquals(2, factory.createCount)

        factory.finish()
        factory.finish()

        assertEquals(listOf("first", "second", "third"), host.titles)
    }

    fun testTheSavedListIsNotOverwrittenWhileTheRestoreChainRuns() {
        configureApps("first", "second")
        saveTabs(
            TuiSessionRecord("first", "first"),
            TuiSessionRecord("second", "second", selected = true),
        )
        val factory = DeferredFactory(List(2) { FakeSession() })
        val (service, _) = newService(factory)

        service.restoreSavedTabs()
        assertEquals(listOf("first", "second"), savedTitles())

        factory.finish()
        assertEquals(listOf("first", "second"), savedTitles())
        assertEquals("second", selectedTitle())

        factory.finish()

        assertEquals(listOf("first", "second"), savedTitles())
        assertEquals("second", selectedTitle())
    }

    fun testTheTabMarkedSelectedIsActiveWhenTheRestoreFinishes() {
        configureApps("first", "second", "third")
        saveTabs(
            TuiSessionRecord("first", "first"),
            TuiSessionRecord("second", "second", selected = true),
            TuiSessionRecord("third", "third"),
        )
        val (service, host) = newService(FakeFactory(List(3) { FakeSession() }))

        service.restoreSavedTabs()

        assertSame(host.tabs[1], host.activeTab())
    }

    fun testTheLastRestoredTabIsActiveWhenNoRecordWasSelected() {
        configureApps("first", "second")
        saveTabs(TuiSessionRecord("first", "first"), TuiSessionRecord("second", "second"))
        val (service, host) = newService(FakeFactory(List(2) { FakeSession() }))

        service.restoreSavedTabs()

        assertSame(host.tabs[1], host.activeTab())
    }

    fun testRestoreOpensNothingWhileTheFeatureIsOff() {
        disableRestore()
        configureApps("first")
        saveTabs(TuiSessionRecord("first", "first"))
        val factory = DeferredFactory(FakeSession())
        val (service, host) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(0, factory.createCount)
        assertTrue(host.tabs.isEmpty())
    }

    fun testRestoringTwiceRestoresTheTabsOnlyOnce() {
        configureApps("first")
        saveTabs(TuiSessionRecord("first", "first"))
        val (service, host) = newService(FakeFactory(List(2) { FakeSession() }))

        service.restoreSavedTabs()
        service.restoreSavedTabs()

        assertEquals(listOf("first"), host.titles)
    }

    fun testARememberedAppThatNoLongerExistsIsSkippedAndTheRestKeepTheirOrder() {
        configureApps("first", "third")
        saveTabs(
            TuiSessionRecord("first", "first"),
            TuiSessionRecord("second", "second"),
            TuiSessionRecord("third", "third"),
        )
        val (service, host) = newService(FakeFactory(List(2) { FakeSession() }))

        service.restoreSavedTabs()

        assertEquals(listOf("first", "third"), host.titles)
        assertEquals(listOf("first", "third"), savedTitles())
    }

    fun testEveryRememberedAppMissingRestoresNothing() {
        saveTabs(TuiSessionRecord("first", "first"), TuiSessionRecord("second", "second"))
        val (service, host) = newService(FakeFactory(emptyList()))

        service.restoreSavedTabs()

        assertTrue(host.tabs.isEmpty())
        assertTrue(savedTabs().isEmpty())
    }

    fun testTheRestoreChainContinuesAfterALaunchFails() {
        configureApps("first", "second")
        saveTabs(TuiSessionRecord("first", "first"), TuiSessionRecord("second", "second"))
        val factory = DeferredFactory(List(2) { FakeSession() })
        val (service, host) = newService(factory)

        service.restoreSavedTabs()
        factory.fail()

        assertEquals(2, factory.createCount)

        factory.finish()

        assertEquals(listOf("second"), host.titles)
    }

    fun testACustomTitleComesBackVerbatim() {
        configureApps("claude")
        saveTabs(TuiSessionRecord("claude", "review agent"))
        val (service, host) = newService(FakeFactory(FakeSession()))

        service.restoreSavedTabs()

        assertEquals(listOf("review agent"), host.titles)
    }

    fun testRestoredTabsAreNumberedAroundATabThatIsAlreadyOpen() {
        configureApps("claude")
        val (service, host) = newService(FakeFactory(List(2) { FakeSession() }))
        service.launchNew("claude", "claude")
        saveTabs(TuiSessionRecord("claude", "claude"))

        service.restoreSavedTabs()

        assertEquals(listOf("claude", "claude 1"), host.titles)
    }

    fun testRestoreNeitherRevealsTheToolWindowNorFocusesASession() {
        configureApps("first", "second")
        saveTabs(
            TuiSessionRecord("first", "first"),
            TuiSessionRecord("second", "second", selected = true),
        )
        val sessions = List(2) { FakeSession() }
        val (service, host) = newService(FakeFactory(sessions))

        service.restoreSavedTabs()

        assertEquals(0, host.showCount)
        assertFalse(host.visible)
        assertTrue(sessions.all { it.focusCount == 0 })
    }
}
