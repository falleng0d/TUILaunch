package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentStateFiles
import com.github.atm1020.tuilaunch.resume.CodexSessionStrategy
import com.github.atm1020.tuilaunch.resume.ShellWords
import com.github.atm1020.tuilaunch.resume.TabIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CodexSessionStrategyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tabUuid = "6a1f0c22-9b74-4a0e-8d3f-2b5c7e91a004"
    private val sessionId = "019a4f3c-7b21-7cd0-9e55-3f1b2a6d8c47"
    private val laterSessionId = "019a52118c334de1af664e2c3b7e9d58"
    private val transcriptPath = "/Users/falleng0d/.codex/sessions/2026/09/08/rollout.jsonl"

    private val tab = TabIdentity(
        tabUuid = tabUuid,
        projectPath = "/Users/falleng0d/Projects/TUILaunch",
        projectHash = "TUILaunch.b2c3d4",
    )

    @Test
    fun theStateFileIsAJsonLinesFileNamedAfterTheTab() {
        val stateDirectory = stateDirectory()
        val strategy = CodexSessionStrategy(stateDirectory)

        assertEquals(stateDirectory.resolve("codex").resolve("$tabUuid.jsonl"), strategy.stateFile(tab))
    }

    @Test
    fun launchAppendsToTheStateFileFromBothHooksAndBypassesTheTrustPrompt() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        assertEquals(codexHookArguments(stateFile), strategy.launchArguments(tab))
    }

    @Test
    fun theLaunchCommandQuotesBothHookOverrides() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        assertEquals(
            "codex -c '${expectedToml("SessionStart", stateFile)}' " +
                "-c '${expectedToml("UserPromptSubmit", stateFile)}' --dangerously-bypass-hook-trust",
            AgentCommand.parse("codex").withArguments(strategy.launchArguments(tab)),
        )
    }

    @Test
    fun prepareLaunchCreatesTheDirectoryTheHookWritesInto() {
        val strategy = CodexSessionStrategy(stateDirectory())

        strategy.prepareLaunch(tab)

        assertTrue(Files.isDirectory(strategy.stateFile(tab).parent))
    }

    @Test
    fun buildingTheArgumentsWritesNothing() {
        val strategy = CodexSessionStrategy(stateDirectory())

        strategy.launchArguments(tab)
        strategy.restoreArguments(tab)

        assertFalse(Files.exists(stateDirectory()))
    }

    @Test
    fun restoreResumesTheRecordedSessionAndKeepsBothHooks() {
        val strategy = CodexSessionStrategy(stateDirectory())
        writeState(strategy.stateFile(tab), record(sessionId, transcriptPath))

        assertEquals(
            listOf("resume", sessionId) + strategy.launchArguments(tab),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun theWrappedRestoreCommandMatchesTheVerifiedPassThroughForm() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)
        writeState(stateFile, record(sessionId, transcriptPath))
        val command = "headroom wrap codex --no-serena --no-tokensave --dangerously-bypass-approvals-and-sandbox"

        assertEquals(
            "$command -- resume $sessionId " +
                "-c ${ShellWords.quote(expectedToml("SessionStart", stateFile))} " +
                "-c ${ShellWords.quote(expectedToml("UserPromptSubmit", stateFile))} " +
                "--dangerously-bypass-hook-trust",
            AgentCommand.parse(command).withArguments(strategy.restoreArguments(tab)),
        )
    }

    @Test
    fun restoreFallsBackToTheLaunchArgumentsWithoutAUsableRecord() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val launch = strategy.launchArguments(tab)

        assertEquals(launch, strategy.restoreArguments(tab))

        writeState(strategy.stateFile(tab), """{"cwd":"/Users/falleng0d/Projects/TUILaunch"}""")
        assertEquals(launch, strategy.restoreArguments(tab))

        writeState(strategy.stateFile(tab), "{not json")
        assertEquals(launch, strategy.restoreArguments(tab))
    }

    @Test
    fun theNewestRecordOfTheStateFileWins() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        writeState(
            stateFile,
            record(sessionId, transcriptPath) + "\n" + record(laterSessionId, transcriptPath) + "\n",
        )

        assertEquals(laterSessionId, strategy.readSessionId(stateFile))
    }

    @Test
    fun aTornLastLineDoesNotHideThePreviousRecord() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        writeState(stateFile, record(sessionId, transcriptPath) + "\n" + """{"session_id":"01""")

        assertEquals(sessionId, strategy.readSessionId(stateFile))
    }

    @Test
    fun aLastLineTornInsideAMultiByteCharacterDoesNotHideThePreviousRecord() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)
        val good = (record(sessionId, transcriptPath) + "\n").toByteArray(Charsets.UTF_8)
        val torn = """{"session_id":"01","prompt":"café""".toByteArray(Charsets.UTF_8)

        Files.createDirectories(stateFile.parent)
        Files.write(stateFile, good + torn.dropLast(1).toByteArray())

        assertEquals(sessionId, strategy.readSessionId(stateFile))
    }

    @Test
    fun onlyTheTailOfAVeryLongStateFileIsRead() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)
        val filler = record(sessionId, transcriptPath) + "\n"

        writeState(stateFile, filler.repeat(2_000) + record(laterSessionId, transcriptPath) + "\n")

        assertEquals(laterSessionId, strategy.readSessionId(stateFile))
    }

    @Test
    fun anEphemeralSideConversationIsSkipped() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        writeState(
            stateFile,
            record(sessionId, transcriptPath) + "\n" +
                """{"session_id":"$laterSessionId","transcript_path":null,"source":"startup"}""" + "\n",
        )

        assertEquals(sessionId, strategy.readSessionId(stateFile))
    }

    @Test
    fun aRecordWithoutAUsableSessionIdIsSkipped() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, "")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, "\n\n")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, """{"session_id":42,"transcript_path":"$transcriptPath"}""")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, """{"session_id":"  ","transcript_path":"$transcriptPath"}""")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, """["$sessionId"]""")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, record(sessionId, transcriptPath))
        assertEquals(sessionId, strategy.readSessionId(stateFile))
    }

    @Test
    fun cleanUpDeletesTheStateFileAndToleratesAMissingOne() {
        val strategy = CodexSessionStrategy(stateDirectory())
        writeState(strategy.stateFile(tab), record(sessionId, transcriptPath))

        runBlocking {
            strategy.cleanUp(tab)
            strategy.cleanUp(tab)
        }

        assertFalse(Files.exists(strategy.stateFile(tab)))
    }

    @Test
    fun aStateFilePathThatWouldBreakTheHookIsRefused() {
        for (name in listOf("say\"hi\"", "back\\slash", "cost\$100", "back`tick`", "two\nlines")) {
            val strategy = CodexSessionStrategy(temporaryFolder.root.toPath().resolve(name))

            assertEquals(name, emptyList<String>(), strategy.hookArguments(strategy.stateFile(tab)))
            assertEquals(emptyList<String>(), strategy.launchArguments(tab))
            assertEquals(emptyList<String>(), strategy.restoreArguments(tab))
            assertEquals("codex", AgentCommand.parse("codex").withArguments(strategy.launchArguments(tab)))
        }
    }

    @Test
    fun aPlainStateFilePathIsManageable() {
        val strategy = CodexSessionStrategy(stateDirectory())

        assertEquals(codexHookArguments(strategy.stateFile(tab)), strategy.hookArguments(strategy.stateFile(tab)))
    }

    @Test
    fun sweepingBothStateDirectoriesKeepsTheTabsThatCameBack() {
        val orphanUuid = "2f8d1b60-77aa-4c31-9e02-5d3c8a1f4b77"
        val directories = listOf(stateDirectory().resolve("claude"), stateDirectory().resolve("codex"))
        for (directory in directories) {
            writeState(directory.resolve("$tabUuid.jsonl"), record(sessionId, transcriptPath))
            writeState(directory.resolve("$tabUuid.json"), record(sessionId, transcriptPath))
            writeState(directory.resolve("$orphanUuid.jsonl"), record(sessionId, transcriptPath))
            writeState(directory.resolve("$orphanUuid.json"), record(sessionId, transcriptPath))
            writeState(directory.resolve("notes.txt"), "keep me")
            writeState(directory.resolve("$tabUuid.json.tmp"), "half written")
            writeState(directory.resolve("$orphanUuid.json.tmp"), "half written")
            writeState(directory.resolve("$orphanUuid.json.4321.tmp"), "half written")
        }

        runBlocking {
            directories.forEach { AgentStateFiles.deleteStateFilesExcept(it, setOf(tabUuid)) }
        }

        for (directory in directories) {
            assertTrue(directory.toString(), Files.exists(directory.resolve("$tabUuid.jsonl")))
            assertTrue(directory.toString(), Files.exists(directory.resolve("$tabUuid.json")))
            assertFalse(directory.toString(), Files.exists(directory.resolve("$orphanUuid.jsonl")))
            assertFalse(directory.toString(), Files.exists(directory.resolve("$orphanUuid.json")))
            assertTrue(directory.toString(), Files.exists(directory.resolve("notes.txt")))
            assertTrue(directory.toString(), Files.exists(directory.resolve("$tabUuid.json.tmp")))
            assertFalse(directory.toString(), Files.exists(directory.resolve("$orphanUuid.json.tmp")))
            assertFalse(directory.toString(), Files.exists(directory.resolve("$orphanUuid.json.4321.tmp")))
        }
    }

    @Test
    fun sweepingAStateDirectoryThatDoesNotExistDoesNothing() {
        val strategy = CodexSessionStrategy(stateDirectory())

        runBlocking { AgentStateFiles.deleteStateFilesExcept(strategy.stateFile(tab).parent, setOf(tabUuid)) }

        assertFalse(Files.exists(strategy.stateFile(tab).parent))
    }

    private fun expectedToml(event: String, stateFile: Path): String = codexHookToml(event, stateFile)

    private fun record(sessionId: String, transcriptPath: String): String =
        """{"session_id":"$sessionId","transcript_path":"$transcriptPath","source":"resume"}"""

    private fun stateDirectory(): Path = temporaryFolder.root.toPath().resolve("agent-sessions")

    private fun writeState(stateFile: Path, content: String) {
        Files.createDirectories(stateFile.parent)
        Files.writeString(stateFile, content)
    }
}
