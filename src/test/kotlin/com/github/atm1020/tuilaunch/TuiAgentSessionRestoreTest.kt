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
private const val CODEX_LATER_SESSION_ID = "019a52118c334de1af664e2c3b7e9d58"
private const val CLAUDE_REPORTED_SESSION = "5c2a7f88-40b6-4d19-b3e7-9a1c0d6f4e22"
private const val CODEX_TRANSCRIPT_PATH = "/Users/falleng0d/.codex/sessions/2026/09/08/rollout.jsonl"
private const val OLDER_OMP_SESSION = "2026-09-07T21-15-03-123Z_019a4f3c7b217cd09e553f1b2a6d8c47.jsonl"
private const val NEWEST_OMP_SESSION = "2026-09-08T09-02-11-000Z_019a52118c334de1af664e2c3b7e9d58.jsonl"
private const val OMP_SESSION_ID = "019a4f3c7b217cd09e553f1b2a6d8c47"
private const val OPENCODE_SESSION_ID = "ses_8a3f1c0d9b2e4a6c8d0f2b4a6c"
private const val AGENT_SESSION_TIMEOUT_SECONDS = 30

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

    fun testAClaudeCommandCarryingItsOwnSettingsKeepsThatFileAlone() {
        val command = "claude --settings /Users/falleng0d/team-settings.json"
        configureApp("claude", command)
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("claude", command)

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(listOf("$command --session-id $tabUuid"), factory.commands)
        assertFalse(Files.exists(claudeStateFile(tabUuid).parent))
    }

    fun testAWrappedClaudeCommandCarryingItsOwnSettingsResumesItsOwnTab() {
        val command = "headroom wrap claude --settings=/Users/falleng0d/team-settings.json"
        configureApp("claude", command)
        write(
            claudeStateFile(TAB_UUID),
            """{"session_id":"$CLAUDE_REPORTED_SESSION"}""",
        )
        writeClaudeTranscript(TAB_UUID)
        saveTab(TuiSessionRecord("claude", "claude", true, TAB_UUID, TAB_UUID, "CLAUDE"))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("$command -- --resume $TAB_UUID"), factory.commands)
    }

    fun testTheStrategyPreparesItsStateBeforeTheArgumentsAreBuilt() {
        configureApp("claude", "claude")
        val strategy = RecordingSessionStrategy()
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)
        service.agentSessionStrategies = { _, _, _ -> strategy }

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
            listOf(
                "codex resume $CODEX_SESSION_ID " +
                    ShellWords.join(codexHookArguments()),
            ),
            factory.commands,
        )
        assertEquals(CODEX_SESSION_ID, savedTabs().single().agentSessionId)
    }

    fun testAFreshCodexTabCarriesTheStateFileInFrontOfTheWrappedCommand() {
        configureApp("codex", "headroom wrap codex --no-serena")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("codex", "headroom wrap codex --no-serena")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(
            listOf(
                "headroom wrap codex --no-serena -- " +
                    ShellWords.join(codexHookArguments()),
            ),
            factory.commands,
        )
        assertTrue(Files.isDirectory(codexStateFile(tabUuid).parent))
    }

    fun testACodexTabWhoseTwoHooksWroteOnOneLineStillFindsItsSession() {
        configureApp("codex", "codex")
        write(
            codexStateFile(TAB_UUID),
            codexHookRecord().trimEnd() + codexHookRecord(CODEX_LATER_SESSION_ID).trimEnd() + "\n\n",
        )
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf(
                "codex resume $CODEX_LATER_SESSION_ID " +
                    ShellWords.join(codexHookArguments()),
            ),
            factory.commands,
        )
        assertEquals(CODEX_LATER_SESSION_ID, savedTabs().single().agentSessionId)
    }

    fun testACodexCommandThatCarriesItsOwnStateFileIsLaunchedUntouched() {
        val command = "TUILAUNCH_CODEX_STATE=/Users/falleng0d/own-codex.jsonl codex"
        configureApp("codex", command)
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("codex", command)

        assertEquals(listOf(command), factory.commands)
        assertFalse(Files.exists(environment.stateDirectory))
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testAFreshOmpTabLoadsTheBundledExtension() {
        configureApp("omp", "omp")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("omp", "omp")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(listOf("omp ${ompSessionArguments(tabUuid)}"), factory.commands)
        assertTrue(Files.isRegularFile(ompFollowSessionExtension()))
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testAWrappedOmpTabTakesTheBundledExtensionThroughHeadroom() {
        configureApp("omp", "headroom wrap omp --no-serena")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("omp", "headroom wrap omp --no-serena")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(
            listOf("headroom wrap omp --no-serena -- ${ompSessionArguments(tabUuid)}"),
            factory.commands,
        )
        assertTrue(Files.isRegularFile(ompFollowSessionExtension()))
    }

    fun testAnOmpTabResumesTheSessionItsExtensionReported() {
        configureApp("omp", "headroom wrap omp")
        val directory = ompSessionDirectory(TAB_UUID)
        val reported = directory.resolve(OLDER_OMP_SESSION)
        write(reported, "{}")
        write(directory.resolve(NEWEST_OMP_SESSION), "{}")
        write(
            directory.resolve("tuilaunch-active.json"),
            """{"sessionFile":"$reported","sessionId":"$OMP_SESSION_ID"}""",
        )
        saveTab(TuiSessionRecord("omp", "omp", true, TAB_UUID, agentCliKind = "OMP"))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf(
                "headroom wrap omp -- ${ompSessionArguments(TAB_UUID)} " +
                    "--resume ${ShellWords.quote(reported.toString())}"
            ),
            factory.commands,
        )
        assertEquals(OMP_SESSION_ID, savedTabs().single().agentSessionId)
    }

    fun testAnOmpTabWithoutAReportResumesTheNewestSessionFileOfItsOwnDirectory() {
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
                "headroom wrap omp -- ${ompSessionArguments(TAB_UUID)} " +
                    "--resume ${ShellWords.quote(newest.toString())}"
            ),
            factory.commands,
        )
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testAnOmpCommandLoadingATrustedExtensionKeepsItsOwnExtensionList() {
        configureApp("omp", "omp --trusted-extension /Users/falleng0d/own.js")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("omp", "omp --trusted-extension /Users/falleng0d/own.js")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(
            listOf(
                "omp --trusted-extension /Users/falleng0d/own.js " +
                    "--session-dir ${ShellWords.quote(ompSessionDirectory(tabUuid).toString())}"
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
        assertEquals(codexEnvironment(relaunchedTabUuid), factory.environments.last())
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

    fun testReopeningTheProjectDropsTheHookStateOfTabsThatAreGone() {
        configureApp("codex", "codex")
        val goneTabUuid = "41d9b7e2-5c08-4a6f-8b13-9e7c0a2f6d54"
        val kept = codexStateFile(TAB_UUID)
        val orphan = codexStateFile(goneTabUuid)
        val claudeOrphan = claudeStateFile(goneTabUuid)
        val openCodeOrphan = openCodeStateFile(goneTabUuid)
        write(kept, codexHookRecord())
        write(orphan, codexHookRecord())
        write(claudeOrphan, """{"session_id":"$CLAUDE_REPORTED_SESSION"}""")
        write(openCodeOrphan, """{"sessionId":"$OPENCODE_SESSION_ID"}""")
        saveTab(TuiSessionRecord("codex", "codex", true, TAB_UUID, CODEX_SESSION_ID, "CODEX"))
        val (service, _) = newService(FakeFactory(FakeSession()))

        service.restoreSavedTabs()

        awaitDeletedFile(orphan)
        awaitDeletedFile(claudeOrphan)
        awaitDeletedFile(openCodeOrphan)
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

    fun testTheTrackerVariablesReachTheTerminalAsRealEnvironmentVariables() {
        configureApp("opencode", "opencode")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("opencode", "opencode")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(listOf(openCodeEnvironment(tabUuid)), factory.environments)
    }

    fun testTheCodexStateFileReachesTheTerminalAsARealEnvironmentVariable() {
        configureApp("codex", "codex")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("codex", "codex")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(listOf(codexEnvironment(tabUuid)), factory.environments)
    }

    fun testAFreshOpenCodeTabCarriesTheTrackerVariablesAndNoArguments() {
        configureApp("opencode", "opencode")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("opencode", "opencode")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(listOf("opencode"), factory.commands)
        assertTrue(Files.isRegularFile(openCodeTuiConfig()))
        assertTrue(Files.isRegularFile(openCodeSessionTracker()))
        assertTrue(Files.isDirectory(openCodeStateFile(tabUuid).parent))
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testAWrappedOpenCodeTabCarriesTheTrackerVariablesBeforeHeadroom() {
        configureApp("opencode", "headroom wrap opencode --no-serena")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("opencode", "headroom wrap opencode --no-serena")

        val tabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertEquals(
            listOf("headroom wrap opencode --no-serena"),
            factory.commands,
        )
    }

    fun testAnOpenCodeTabComesBackToTheSessionItsTrackerReported() {
        configureApp("opencode", "opencode")
        write(openCodeStateFile(TAB_UUID), """{"sessionId":"$OPENCODE_SESSION_ID"}""" + "\n")
        saveTab(TuiSessionRecord("opencode", "opencode", true, TAB_UUID, agentCliKind = "OPENCODE"))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf("opencode --session $OPENCODE_SESSION_ID"),
            factory.commands,
        )
        assertEquals(OPENCODE_SESSION_ID, savedTabs().single().agentSessionId)
    }

    fun testAWrappedOpenCodeTabResumesThroughThePassThrough() {
        configureApp("opencode", "headroom wrap opencode --no-serena")
        write(openCodeStateFile(TAB_UUID), """{"sessionId":"$OPENCODE_SESSION_ID"}""" + "\n")
        saveTab(TuiSessionRecord("opencode", "opencode", true, TAB_UUID, agentCliKind = "OPENCODE"))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(
            listOf(
                "headroom wrap opencode --no-serena " +
                    "-- --session $OPENCODE_SESSION_ID"
            ),
            factory.commands,
        )
    }

    fun testAnOpenCodeTabWithoutAReportComesBackFreshWhateverWasRemembered() {
        configureApp("opencode", "opencode")
        saveTab(TuiSessionRecord("opencode", "opencode", true, TAB_UUID, CODEX_SESSION_ID, "CODEX"))
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.restoreSavedTabs()

        assertEquals(listOf("opencode"), factory.commands)
        assertNull(savedTabs().single().agentSessionId)
        assertEquals("OPENCODE", savedTabs().single().agentCliKind)
    }

    fun testClosingAnOpenCodeTabDeletesTheSessionItsTrackerReported() {
        configureApp("opencode", "opencode")
        val (service, _) = newService(FakeFactory(FakeSession()))
        service.launchNew("opencode", "opencode")
        val stateFile = openCodeStateFile(savedTabs().single().tabUuid!!)
        write(stateFile, """{"sessionId":"$OPENCODE_SESSION_ID"}""" + "\n")

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        awaitDeletedFile(stateFile)
    }

    fun testAReopenedOpenCodeTabThatEndsRightAwayComesBackAsANewTab() {
        configureApp("opencode", "opencode")
        saveTab(TuiSessionRecord("opencode", "opencode", true, TAB_UUID, agentCliKind = "OPENCODE"))
        write(openCodeStateFile(TAB_UUID), """{"sessionId":"$OPENCODE_SESSION_ID"}""")
        val sessions = List(2) { FakeSession() }
        val factory = FakeFactory(sessions)
        val (service, _) = newService(factory)

        service.restoreSavedTabs()
        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val relaunchedTabUuid = requireNotNull(savedTabs().single().tabUuid)
        assertTrue(relaunchedTabUuid, relaunchedTabUuid != TAB_UUID)
        assertEquals(
            listOf(
                "opencode --session $OPENCODE_SESSION_ID",
                "opencode",
            ),
            factory.commands,
        )
    }

    fun testAReopenedTabThatAskedForNoSessionIsNotStartedAgain() {
        configureApp("opencode", "opencode")
        saveTab(TuiSessionRecord("opencode", "opencode", true, TAB_UUID, agentCliKind = "OPENCODE"))
        val sessions = List(2) { FakeSession() }
        val factory = FakeFactory(sessions)
        val (service, _) = newService(factory)

        service.restoreSavedTabs()
        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf("opencode"), factory.commands)
        assertTrue(savedTabs().toString(), savedTabs().isEmpty())
    }

    fun testTheSettingOffLaunchesAnOpenCodeTabExactlyAsConfigured() {
        TuiLauncherSettings.getInstance().state.restoreAgentSessions = false
        configureApp("opencode", "opencode")
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("opencode", "opencode")

        assertEquals(listOf("opencode"), factory.commands)
        assertFalse(Files.exists(environment.stateDirectory))
        assertFalse(Files.exists(environment.bundledDirectory))
        assertNull(savedTabs().single().agentSessionId)
    }

    fun testAnOpenCodeCommandThatCarriesItsOwnTuiConfigIsLaunchedUntouched() {
        val command = "OPENCODE_TUI_CONFIG=/Users/falleng0d/own-tui.json opencode"
        configureApp("opencode", command)
        val factory = FakeFactory(FakeSession())
        val (service, _) = newService(factory)

        service.launchNew("opencode", command)

        assertEquals(listOf(command), factory.commands)
        assertFalse(Files.exists(environment.stateDirectory))
        assertFalse(Files.exists(environment.bundledDirectory))
        assertNull(savedTabs().single().agentSessionId)
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

    private fun newService(sessionFactory: TerminalSessionFactory): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        service.host = host
        service.sessionFactory = sessionFactory
        service.agentSessionEnvironment = { environment }
        return service to host
    }

    private fun awaitDeletedFile(file: Path) {
        PlatformTestUtil.waitWithEventsDispatching(
            "The agent state file $file was never deleted",
            { !Files.exists(file) },
            AGENT_SESSION_TIMEOUT_SECONDS,
        )
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

    private fun codexEnvironment(tabUuid: String): Map<String, String> =
        mapOf("TUILAUNCH_CODEX_STATE" to codexStateFile(tabUuid).toString())

    private fun codexHookRecord(sessionId: String = CODEX_SESSION_ID): String =
        """{"session_id":"$sessionId","transcript_path":"$CODEX_TRANSCRIPT_PATH","source":"startup"}""" + "\n"

    private fun claudeStateFile(tabUuid: String): Path =
        environment.stateDirectory.resolve("claude").resolve("$tabUuid.json")

    private fun claudeSettings(tabUuid: String): String =
        "--settings ${ShellWords.quote(claudeHookSettings(claudeStateFile(tabUuid)))}"

    private fun ompSessionDirectory(tabUuid: String): Path = environment.ompRoot
        .resolve("sessions")
        .resolve("--tuilaunch-${project.locationHash}-$tabUuid--")

    private fun ompFollowSessionExtension(): Path = environment.bundledDirectory
        .resolve("omp")
        .resolve("tuilaunch-follow-session.js")

    private fun ompSessionArguments(tabUuid: String): String =
        "--session-dir ${ShellWords.quote(ompSessionDirectory(tabUuid).toString())} " +
            "--hook ${ShellWords.quote(ompFollowSessionExtension().toString())}"

    private fun openCodeStateFile(tabUuid: String): Path =
        environment.stateDirectory.resolve("opencode").resolve("$tabUuid.json")

    private fun openCodeTuiConfig(): Path = environment.bundledDirectory
        .resolve("opencode")
        .resolve("tui.json")

    private fun openCodeSessionTracker(): Path = environment.bundledDirectory
        .resolve("opencode")
        .resolve("tuilaunch-session-tracker.js")

    private fun openCodeEnvironment(tabUuid: String): Map<String, String> = mapOf(
        "OPENCODE_TUI_CONFIG" to openCodeTuiConfig().toString(),
        "TUILAUNCH_OPENCODE_STATE" to openCodeStateFile(tabUuid).toString(),
    )

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
