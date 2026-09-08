package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.ClaudeProjectPath
import com.github.atm1020.tuilaunch.resume.OpenCodeApi
import com.github.atm1020.tuilaunch.resume.OpenCodeSessionStrategy
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
private const val CLAUDE_REPORTED_SESSION = "5c2a7f88-40b6-4d19-b3e7-9a1c0d6f4e22"
private const val CODEX_TRANSCRIPT_PATH = "/Users/falleng0d/.codex/sessions/2026/09/08/rollout.jsonl"
private const val OLDER_OMP_SESSION = "2026-09-07T21-15-03-123Z_019a4f3c7b217cd09e553f1b2a6d8c47.jsonl"
private const val NEWEST_OMP_SESSION = "2026-09-08T09-02-11-000Z_019a52118c334de1af664e2c3b7e9d58.jsonl"
private const val OPENCODE_PORT = 45123
private const val AGENT_SESSION_TIMEOUT_SECONDS = 30
private const val QUIET_MILLIS = 200L
private const val QUIET_POLL_MILLIS = 5L

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

        assertEquals(
            listOf("headroom wrap claude --no-serena -- ${claudeSettings(TAB_UUID)} --resume $TAB_UUID"),
            factory.commands,
        )
    }

    fun testAWrappedClaudeTabWithoutATranscriptComesBackWithItsOwnSessionId() {
        configureApp("claude", "headroom wrap claude --no-serena")
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf("headroom wrap claude --no-serena -- ${claudeSettings(TAB_UUID)} --session-id $TAB_UUID"),
            factory.commands,
        )
    }

    fun testAnUnwrappedClaudeTabTakesTheResumeArgumentWithoutASeparator() {
        configureApp("claude", "claude")
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("claude ${claudeSettings(TAB_UUID)} --resume $TAB_UUID"), factory.commands)
    }

    fun testAFreshClaudeTabPinsItsTabUuidAsTheSessionId() {
        configureApp("claude", "claude")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("claude", "claude")

        val record = savedTabs().single()
        val tabUuid = requireNotNull(record.tabUuid)
        assertEquals(tabUuid, UUID.fromString(tabUuid).toString())
        assertEquals(listOf("claude ${claudeSettings(tabUuid)} --session-id $tabUuid"), factory.commands)
        assertEquals(tabUuid, record.agentSessionId)
        assertTrue(Files.isDirectory(claudeStateFile(tabUuid).parent))
    }

    fun testAClaudeTabResumesTheSessionItsHookReported() {
        configureApp("claude", "claude")
        val transcript = environment.claudeHome.resolve("projects").resolve("elsewhere").resolve("moved.jsonl")
        write(transcript, "{}\n")
        write(
            claudeStateFile(TAB_UUID),
            """{"session_id":"$CLAUDE_REPORTED_SESSION","transcript_path":"$transcript"}""",
        )
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID, TAB_UUID, "CLAUDE"))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf("claude ${claudeSettings(TAB_UUID)} --resume $CLAUDE_REPORTED_SESSION"),
            factory.commands,
        )
        assertEquals(CLAUDE_REPORTED_SESSION, savedTabs().single().agentSessionId)
    }

    fun testAClaudeTabWhoseReportedTranscriptIsGoneFallsBackToItsOwnTab() {
        configureApp("claude", "claude")
        write(
            claudeStateFile(TAB_UUID),
            """{"session_id":"$CLAUDE_REPORTED_SESSION","transcript_path":"/nowhere/gone.jsonl"}""",
        )
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID, TAB_UUID, "CLAUDE"))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("claude ${claudeSettings(TAB_UUID)} --resume $TAB_UUID"), factory.commands)
        assertEquals(TAB_UUID, savedTabs().single().agentSessionId)
    }

    fun testTheStrategyPreparesItsStateBeforeTheArgumentsAreBuilt() {
        configureApp("claude", "claude")
        val strategy = RecordingSessionStrategy()
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)
        service.agentSessionStrategies = { _, _ -> strategy }

        service.launchNew("claude", "claude")

        assertEquals(listOf("prepareLaunch", "launchArguments"), strategy.calls)
        assertEquals(listOf("claude --recorded"), factory.commands)
    }

    fun testClosingAClaudeTabDeletesTheSessionItsHookReported() {
        configureApp("claude", "claude")
        val (service, _) = newService(FakeFactory(FakeSession()))
        service.launchNew("claude", "claude")
        val stateFile = claudeStateFile(savedTabs().single().tabUuid!!)
        write(stateFile, """{"session_id":"$CLAUDE_REPORTED_SESSION"}""")

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        awaitDeletedFile(stateFile)
    }

    fun testACodexTabResumesTheSessionItsHookRecorded() {
        configureApp("codex", "codex")
        val stateFile = codexStateFile(TAB_UUID)
        write(stateFile, codexHookRecord())
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf("codex resume $CODEX_SESSION_ID ${ShellWords.join(codexHookArguments(stateFile))}"),
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

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(tabUuid, UUID.fromString(tabUuid).toString())
        assertEquals(listOf("claude ${claudeSettings(tabUuid)} --session-id $tabUuid"), factory.commands)
    }

    fun testAReopenedTabThatEndsRightAwayComesBackAsANewTabInTheSamePlace() {
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
            listOf("claude ${claudeSettings(TAB_UUID)} --resume $TAB_UUID", "second", "third"),
            factory.commands.take(3),
        )
        val relaunchedTabUuid = savedTabs().first().tabUuid!!
        assertEquals(relaunchedTabUuid, UUID.fromString(relaunchedTabUuid).toString())
        assertTrue(relaunchedTabUuid, relaunchedTabUuid != TAB_UUID)
        assertEquals(
            "claude ${claudeSettings(relaunchedTabUuid)} --session-id $relaunchedTabUuid",
            factory.commands.last(),
        )
        assertEquals(4, factory.commands.size)
        assertEquals(listOf("claude", "second", "third"), savedTabs().map { it.title })
    }

    fun testARelaunchedTabDropsTheHookStateOfTheSessionThatDied() {
        configureApp("codex", "codex")
        val stateFile = codexStateFile(TAB_UUID)
        write(stateFile, codexHookRecord())
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID, CODEX_SESSION_ID, "CODEX"))
        val sessions = List(2) { FakeSession() }
        val factory = FakeFactory(sessions)
        val (service, _) = newService(factory)

        service.restoreSavedTabs()
        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        awaitDeletedFile(stateFile)
        val relaunchedTabUuid = savedTabs().single().tabUuid!!
        assertTrue(relaunchedTabUuid, relaunchedTabUuid != TAB_UUID)
        assertTrue(factory.commands.last(), factory.commands.last().contains(relaunchedTabUuid))
    }

    fun testATabTheUserClosedIsNotStartedAgainWhenItsProcessEnds() {
        configureApp("claude", "claude")
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID, agentCliKind = "CLAUDE"))
        val sessions = List(2) { FakeSession() }
        val factory = FakeFactory(sessions)
        val (service, host) = newService(factory)

        service.restoreSavedTabs()
        service.closeActiveTui()
        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf("claude ${claudeSettings(TAB_UUID)} --resume $TAB_UUID"), factory.commands)
        assertTrue(host.tabs.isEmpty())
        assertTrue(savedTabs().isEmpty())
    }

    fun testASessionIdRememberedForAnotherCliIsNotHandedToTheNewOne() {
        configureApp("agent", "opencode")
        saveTab(TuiSessionRecord("agent", "agent", true, TAB_UUID, CODEX_SESSION_ID, "CODEX"))
        val api = RecordingOpenCodeApi()
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory, api)

        service.restoreSavedTabs()

        assertEquals(listOf("opencode --port $OPENCODE_PORT --hostname 127.0.0.1"), factory.commands)
        assertEquals(CREATED_OPENCODE_SESSION, awaitRecordedAgentSessionId())
        assertEquals("OPENCODE", savedTabs().single().agentCliKind)
    }

    fun testASessionIdRememberedForTheSameCliIsUsedAgain() {
        configureApp("agent", "opencode")
        saveTab(TuiSessionRecord("agent", "agent", true, TAB_UUID, CREATED_OPENCODE_SESSION, "OPENCODE"))
        val api = RecordingOpenCodeApi()
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory, api)

        service.restoreSavedTabs()

        assertEquals(
            listOf("opencode --port $OPENCODE_PORT --hostname 127.0.0.1 --session $CREATED_OPENCODE_SESSION"),
            factory.commands,
        )
    }

    fun testReopeningTheProjectDropsTheHookStateOfTabsThatAreGone() {
        configureApp("codex", "codex")
        val goneTabUuid = "41d9b7e2-5c08-4a6f-8b13-9e7c0a2f6d54"
        val kept = codexStateFile(TAB_UUID)
        val orphan = codexStateFile(goneTabUuid)
        val claudeOrphan = claudeStateFile(goneTabUuid)
        write(kept, codexHookRecord())
        write(orphan, codexHookRecord())
        write(claudeOrphan, """{"session_id":"$CLAUDE_REPORTED_SESSION"}""")
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID, CODEX_SESSION_ID, "CODEX"))
        val (service, _) = newService(FakeFactory(FakeSession()))

        service.restoreSavedTabs()

        awaitDeletedFile(orphan)
        awaitDeletedFile(claudeOrphan)
        assertTrue(Files.exists(kept))
    }

    fun testACodexTabThatEndsTwiceAfterAReopenIsNotStartedAThirdTime() {
        configureApp("codex", "codex")
        write(codexStateFile(TAB_UUID), codexHookRecord())
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
        write(stateFile, codexHookRecord())

        host.removeTab(host.tabs.single())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        awaitDeletedFile(stateFile)
    }

    fun testTheCloseActionDeletesTheHookStateOfACodexTab() {
        configureApp("codex", "codex")
        val (service, _) = newService(FakeFactory(FakeSession()))
        service.launchNew("codex", "codex")
        val stateFile = codexStateFile(savedTabs().single().tabUuid!!)
        write(stateFile, codexHookRecord())

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        awaitDeletedFile(stateFile)
    }

    fun testAFreshOpenCodeTabRecordsTheSessionItsServerCreated() {
        configureApp("opencode", "opencode")
        val api = RecordingOpenCodeApi(healthyFromCall = 3)
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory, api)

        service.launchNew("opencode", "opencode")

        assertEquals(CREATED_OPENCODE_SESSION, awaitRecordedAgentSessionId())
        assertEquals(listOf("opencode --port $OPENCODE_PORT --hostname 127.0.0.1"), factory.commands)
        assertEquals(
            listOf(CreatedSession(projectPath(), savedTabs().single().tabUuid!!)),
            api.creations,
        )
        assertEquals(listOf(CREATED_OPENCODE_SESSION), api.selections)
    }

    fun testAWrappedOpenCodeTabRecordsTheSessionItsServerCreated() {
        configureApp("opencode", "headroom wrap opencode --no-serena")
        val api = RecordingOpenCodeApi()
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory, api)

        service.launchNew("opencode", "headroom wrap opencode --no-serena")

        assertEquals(CREATED_OPENCODE_SESSION, awaitRecordedAgentSessionId())
        assertEquals(
            listOf("headroom wrap opencode --no-serena -- --port $OPENCODE_PORT --hostname 127.0.0.1"),
            factory.commands,
        )
    }

    fun testAnOpenCodeTabComesBackToTheSessionItRecorded() {
        configureApp("opencode", "opencode")
        saveTab(TuiSessionRecord("opencode", "opencode", true, TAB_UUID, CREATED_OPENCODE_SESSION))
        val api = RecordingOpenCodeApi()
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory, api)

        service.restoreSavedTabs()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(
            listOf(
                "opencode --port $OPENCODE_PORT --hostname 127.0.0.1 --session $CREATED_OPENCODE_SESSION"
            ),
            factory.commands,
        )
        assertEquals(CREATED_OPENCODE_SESSION, savedTabs().single().agentSessionId)
        assertStaysFalse("A reopened opencode tab asked its server for another session") {
            api.healthCalls > 0 || api.creations.isNotEmpty()
        }
    }

    fun testTheSettingOffLeavesAnOpenCodeTabWithoutAnyServerWork() {
        TuiLauncherSettings.getInstance().state.restoreAgentSessions = false
        configureApp("opencode", "opencode")
        val api = RecordingOpenCodeApi()
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory, api)

        service.launchNew("opencode", "opencode")

        assertEquals(listOf("opencode"), factory.commands)
        assertStaysFalse("An unmanaged opencode tab talked to its server") {
            api.healthCalls > 0 || api.creations.isNotEmpty()
        }
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testAnOpenCodeTabClosedBeforeItsServerAnsweredCreatesNoSession() {
        configureApp("opencode", "opencode")
        val api = RecordingOpenCodeApi(healthyFromCall = RecordingOpenCodeApi.NEVER_HEALTHY)
        val (service, _) = newService(FakeFactory(FakeSession()), api)
        service.launchNew("opencode", "opencode")

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertStaysFalse("A closed opencode tab still created a session") { api.creations.isNotEmpty() }
        assertTrue(savedTabs().isEmpty())
    }

    fun testClosingAnOpenCodeTabDiscardsASessionNobodyPromptedIn() {
        configureApp("opencode", "opencode")
        val api = RecordingOpenCodeApi(messages = 0)
        val (service, _) = newService(FakeFactory(FakeSession()), api)
        service.launchNew("opencode", "opencode")
        awaitRecordedAgentSessionId()

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf(CREATED_OPENCODE_SESSION), awaitDiscardedSessions(api))
    }

    fun testAnOpenCodeTabWhoseProcessEndsKeepsItsSession() {
        configureApp("opencode", "opencode")
        val api = RecordingOpenCodeApi(messages = 0)
        val session = FakeSession()
        val (service, _) = newService(FakeFactory(session), api)
        service.launchNew("opencode", "opencode")
        awaitRecordedAgentSessionId()

        session.terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertStaysFalse("An opencode session was discarded without the user closing its tab") {
            api.deletions.isNotEmpty()
        }
    }

    fun testACodexTabWhoseProcessEndsKeepsItsHookState() {
        configureApp("codex", "codex")
        val session = FakeSession()
        val (service, _) = newService(FakeFactory(session))
        service.launchNew("codex", "codex")
        val stateFile = codexStateFile(savedTabs().single().tabUuid!!)
        write(stateFile, codexHookRecord())

        session.terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(Files.exists(stateFile))
    }

    private fun newService(
        sessionFactory: TerminalSessionFactory,
        openCodeApi: OpenCodeApi? = null,
    ): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        service.host = host
        service.sessionFactory = sessionFactory
        service.agentSessionEnvironment = { environment }
        if (openCodeApi != null) {
            service.agentSessionStrategies = { _, _ -> openCodeStrategyFor(openCodeApi) }
        }
        return service to host
    }

    private fun openCodeStrategyFor(api: OpenCodeApi): OpenCodeSessionStrategy = OpenCodeSessionStrategy(
        freePort = { OPENCODE_PORT },
        apiFactory = { api },
        pollIntervalMillis = 5,
        startupTimeoutMillis = 5_000,
    )

    private fun awaitRecordedAgentSessionId(): String {
        PlatformTestUtil.waitWithEventsDispatching(
            "The tab never recorded the session its agent server created",
            { savedTabs().singleOrNull()?.agentSessionId != null },
            AGENT_SESSION_TIMEOUT_SECONDS,
        )
        return requireNotNull(savedTabs().single().agentSessionId)
    }

    private fun awaitDiscardedSessions(api: RecordingOpenCodeApi): List<String> {
        PlatformTestUtil.waitWithEventsDispatching(
            "The empty agent session was never discarded",
            { api.deletions.isNotEmpty() },
            AGENT_SESSION_TIMEOUT_SECONDS,
        )
        return api.deletions
    }

    private fun awaitDeletedFile(file: Path) {
        PlatformTestUtil.waitWithEventsDispatching(
            "The agent state file $file was never deleted",
            { !Files.exists(file) },
            AGENT_SESSION_TIMEOUT_SECONDS,
        )
    }

    private fun assertStaysFalse(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + QUIET_MILLIS
        while (System.currentTimeMillis() < deadline) {
            assertFalse(message, condition())
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(QUIET_POLL_MILLIS)
        }
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
        environment.stateDirectory.resolve("codex").resolve("$tabUuid.jsonl")

    private fun codexHookRecord(sessionId: String = CODEX_SESSION_ID): String =
        """{"session_id":"$sessionId","transcript_path":"$CODEX_TRANSCRIPT_PATH","source":"startup"}""" + "\n"

    private fun claudeStateFile(tabUuid: String): Path =
        environment.stateDirectory.resolve("claude").resolve("$tabUuid.json")

    private fun claudeSettings(tabUuid: String): String =
        "--settings ${ShellWords.quote(claudeHookSettings(claudeStateFile(tabUuid)))}"

    private fun ompSessionDirectory(tabUuid: String): Path = environment.ompRoot
        .resolve("sessions")
        .resolve("--tuilaunch-${project.locationHash}-$tabUuid--")

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
