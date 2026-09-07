package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.ClaudeProjectPath
import com.github.atm1020.tuilaunch.resume.ShellWords
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.services.TuiOpenTabsService
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val TAB_UUID = "b7c1e0d4-3a52-4f19-8c7d-2e6f5a9b1c30"
private const val CODEX_SESSION_ID = "019a4f3c-7b21-7cd0-9e55-3f1b2a6d8c47"
private const val OLDER_OMP_SESSION = "2026-09-07T21-15-03-123Z_019a4f3c7b217cd09e553f1b2a6d8c47.jsonl"
private const val NEWEST_OMP_SESSION = "2026-09-08T09-02-11-000Z_019a52118c334de1af664e2c3b7e9d58.jsonl"

class TuiAgentSessionRestoreTest : BasePlatformTestCase() {

    private lateinit var environment: AgentSessionEnvironment

    override fun setUp() {
        super.setUp()
        environment = temporaryAgentSessionEnvironment()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
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
                restoreAgentSessions = true
            }
            TuiOpenTabsService.getInstance(project).replaceTabs(emptyList())
        } finally {
            super.tearDown()
        }
    }

    fun testAWrappedClaudeTabResumesTheTranscriptOfItsOwnTab() {
        configureApp("claude", "headroom wrap claude --no-serena")
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("headroom wrap claude --no-serena -- --resume $TAB_UUID"), factory.commands)
    }

    fun testAWrappedClaudeTabWithoutATranscriptComesBackWithItsOwnSessionId() {
        configureApp("claude", "headroom wrap claude --no-serena")
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("headroom wrap claude --no-serena -- --session-id $TAB_UUID"), factory.commands)
    }

    fun testAnUnwrappedClaudeTabTakesTheResumeArgumentWithoutASeparator() {
        configureApp("claude", "claude")
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("claude --resume $TAB_UUID"), factory.commands)
    }

    fun testAFreshClaudeTabPinsItsTabUuidAsTheSessionId() {
        configureApp("claude", "claude")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("claude", "claude")

        val record = savedTabs().single()
        assertEquals(record.tabUuid, UUID.fromString(record.tabUuid).toString())
        assertEquals(listOf("claude --session-id ${record.tabUuid}"), factory.commands)
        assertEquals(record.tabUuid, record.agentSessionId)
    }

    fun testACodexTabResumesTheSessionItsHookRecorded() {
        configureApp("codex", "codex")
        val stateFile = codexStateFile(TAB_UUID)
        write(stateFile, """{"session_id":"$CODEX_SESSION_ID","source":"startup"}""")
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf(
                "codex resume $CODEX_SESSION_ID --dangerously-bypass-hook-trust " +
                    "-c ${ShellWords.quote(codexHookToml(stateFile))}"
            ),
            factory.commands,
        )
        assertEquals(CODEX_SESSION_ID, savedTabs().single().agentSessionId)
    }

    fun testAnOmpTabResumesTheNewestSessionFileOfItsOwnDirectory() {
        configureApp("omp", "headroom wrap omp")
        val directory = ompSessionDirectory(TAB_UUID)
        write(directory.resolve(OLDER_OMP_SESSION), "{}")
        val newest = directory.resolve(NEWEST_OMP_SESSION)
        write(newest, "{}")
        saveTab(TuiSessionRecord("omp", "omp", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf(
                "headroom wrap omp -- --session-dir ${ShellWords.quote(directory.toString())} " +
                    "--resume ${ShellWords.quote(newest.toString())}"
            ),
            factory.commands,
        )
    }

    fun testAnAppThatIsNoCodingAgentIsLaunchedExactlyAsConfigured() {
        configureApp("lazygit", "lazygit")
        saveTab(TuiSessionRecord("lazygit", "git", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("lazygit"), factory.commands)
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testACommandThatPicksItsOwnSessionIsLeftAlone() {
        val command = "claude --resume 42a1b6c8-0000-4000-8000-000000000000"
        configureApp("claude", command)
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf(command), factory.commands)
    }

    fun testTheSettingOffLaunchesTheConfiguredCommandAndWritesNoState() {
        TuiLauncherSettings.getInstance().state.restoreAgentSessions = false
        configureApp("codex", "codex")
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("codex"), factory.commands)
        assertFalse(Files.exists(environment.stateDirectory))
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testATabUuidSurvivesAReopen() {
        configureApp("claude", "claude")
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val (service, _) = newService(FakeFactory(FakeSession()))

        service.restoreSavedTabs()

        assertEquals(TAB_UUID, savedTabs().single().tabUuid)
    }

    fun testARecordWithoutATabUuidGetsOneOnItsFirstReopen() {
        configureApp("claude", "claude")
        saveTab(TuiSessionRecord("claude", "claude", true))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        val record = savedTabs().single()
        assertEquals(record.tabUuid, UUID.fromString(record.tabUuid).toString())
        assertEquals(listOf("claude --session-id ${record.tabUuid}"), factory.commands)
    }

    fun testAReopenedTabThatEndsRightAwayComesBackFreshInTheSamePlace() {
        configureApp("claude", "claude")
        configureApp("second", "second")
        configureApp("third", "third")
        writeClaudeTranscript(TAB_UUID)
        saveTabs(
            TuiSessionRecord("claude", "claude", true, TAB_UUID),
            TuiSessionRecord("second", "second"),
            TuiSessionRecord("third", "third"),
        )
        val sessions = List(4) { FakeSession() }
        val factory = FakeFactory(sessions)
        val (service, _) = newService(factory)

        service.restoreSavedTabs()
        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(
            listOf("claude --resume $TAB_UUID", "second", "third", "claude --session-id $TAB_UUID"),
            factory.commands,
        )
        assertEquals(listOf("claude", "second", "third"), savedTabs().map { it.title })
        assertEquals(TAB_UUID, savedTabs().first().tabUuid)
    }

    fun testACodexTabThatEndsTwiceAfterAReopenIsNotStartedAThirdTime() {
        configureApp("codex", "codex")
        write(codexStateFile(TAB_UUID), """{"session_id":"$CODEX_SESSION_ID"}""")
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID, CODEX_SESSION_ID))
        val sessions = List(2) { FakeSession() }
        val factory = FakeFactory(sessions)
        val (service, host) = newService(factory)

        service.restoreSavedTabs()
        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertNull(savedTabs().single().agentSessionId)

        sessions[1].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(2, factory.commands.size)
        assertTrue(host.tabs.isEmpty())
        assertTrue(savedTabs().isEmpty())
    }

    fun testAFreshTabThatEndsRightAwayIsNotStartedAgain() {
        configureApp("claude", "claude")
        val session = FakeSession()
        val factory = FakeFactory(session)
        val (service, host) = newService(factory)

        service.launchNew("claude", "claude")
        session.terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, factory.commands.size)
        assertTrue(host.tabs.isEmpty())
    }

    fun testAReopenedTabThatRanForAWhileIsNotStartedAgain() {
        configureApp("claude", "claude")
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val session = FakeSession()
        val factory = FakeFactory(session)
        val (service, host) = newService(factory)
        var now = 1_000_000L
        service.clock = { now }

        service.restoreSavedTabs()
        now += 15_001
        session.terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, factory.commands.size)
        assertTrue(host.tabs.isEmpty())
    }

    fun testClosingACodexTabFromTheTabStripDeletesItsHookState() {
        configureApp("codex", "codex")
        val (service, host) = newService(FakeFactory(FakeSession()))
        service.launchNew("codex", "codex")
        val stateFile = codexStateFile(savedTabs().single().tabUuid!!)
        write(stateFile, """{"session_id":"$CODEX_SESSION_ID"}""")

        host.removeTab(host.tabs.single())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(Files.exists(stateFile))
    }

    fun testTheCloseActionDeletesTheHookStateOfACodexTab() {
        configureApp("codex", "codex")
        val (service, _) = newService(FakeFactory(FakeSession()))
        service.launchNew("codex", "codex")
        val stateFile = codexStateFile(savedTabs().single().tabUuid!!)
        write(stateFile, """{"session_id":"$CODEX_SESSION_ID"}""")

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(Files.exists(stateFile))
    }

    fun testACodexTabWhoseProcessEndsKeepsItsHookState() {
        configureApp("codex", "codex")
        val session = FakeSession()
        val (service, _) = newService(FakeFactory(session))
        service.launchNew("codex", "codex")
        val stateFile = codexStateFile(savedTabs().single().tabUuid!!)
        write(stateFile, """{"session_id":"$CODEX_SESSION_ID"}""")

        session.terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(Files.exists(stateFile))
    }

    private fun newService(sessionFactory: TerminalSessionFactory): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project)
        val host = FakeHost()
        service.host = host
        service.sessionFactory = sessionFactory
        service.agentSessionEnvironment = { environment }
        return service to host
    }

    private fun configureApp(name: String, command: String) {
        TuiLauncherSettings.getInstance().state.tuiApps.add(TuiAppConfig(name = name, command = command))
    }

    private fun saveTab(record: TuiSessionRecord) {
        saveTabs(record)
    }

    private fun saveTabs(vararg records: TuiSessionRecord) {
        TuiOpenTabsService.getInstance(project).replaceTabs(records.toList())
    }

    private fun savedTabs(): List<TuiSessionRecord> = TuiOpenTabsService.getInstance(project).state.tabs

    private fun projectPath(): String = requireNotNull(project.basePath)

    private fun writeClaudeTranscript(tabUuid: String) {
        write(
            environment.claudeHome
                .resolve("projects")
                .resolve(ClaudeProjectPath.escape(projectPath()))
                .resolve("$tabUuid.jsonl"),
            "{}\n",
        )
    }

    private fun codexStateFile(tabUuid: String): Path =
        environment.stateDirectory.resolve("codex").resolve("$tabUuid.json")

    private fun codexHookToml(stateFile: Path): String =
        """hooks.SessionStart=[{hooks=[{type="command",command="cat > \"$stateFile\"",async=true,timeout=5}]}]"""

    private fun ompSessionDirectory(tabUuid: String): Path = environment.ompRoot
        .resolve("sessions")
        .resolve("--tuilaunch-${project.locationHash}-$tabUuid--")

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
