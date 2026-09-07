package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.OmpSessionStrategy
import com.github.atm1020.tuilaunch.resume.RememberedSession
import com.github.atm1020.tuilaunch.resume.ShellWords
import com.github.atm1020.tuilaunch.resume.TabIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class OmpSessionStrategyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tabUuid = "c4d8e21b-6f30-4a77-b0c2-9e5d1a3f8b62"

    private val tab = TabIdentity(
        tabUuid = tabUuid,
        projectPath = "/Users/falleng0d/Projects/TUILaunch",
        projectHash = "TUILaunch.b2c3d4",
    )

    @Test
    fun theSessionDirectorySitsOneLevelUnderTheSessionsRoot() {
        val root = root()
        val strategy = OmpSessionStrategy(root)

        assertEquals(
            root.resolve("sessions").resolve("--tuilaunch-TUILaunch.b2c3d4-$tabUuid--"),
            strategy.sessionDirectory(tab),
        )
    }

    @Test
    fun launchArgumentsPointOmpAtTheTabDirectory() {
        val strategy = OmpSessionStrategy(root())

        assertEquals(
            listOf("--session-dir", strategy.sessionDirectory(tab).toString()),
            strategy.launchArguments(tab),
        )
    }

    @Test
    fun restorePicksTheNewestSessionFileByName() {
        val strategy = OmpSessionStrategy(root())
        val directory = strategy.sessionDirectory(tab)
        writeSession(directory, "2026-09-07T21-15-03-123Z_019a4f3c7b217cd09e553f1b2a6d8c47.jsonl")
        val newest = writeSession(directory, "2026-09-08T09-02-11-000Z_019a52118c334de1af664e2c3b7e9d58.jsonl")

        assertEquals(
            listOf("--session-dir", directory.toString(), "--resume", newest.toString()),
            strategy.restoreArguments(tab, RememberedSession()),
        )
    }

    @Test
    fun restoreFallsBackToTheModificationTimeWhenNamesCarryNoTimestamp() {
        val strategy = OmpSessionStrategy(root())
        val directory = strategy.sessionDirectory(tab)
        val older = writeSession(directory, "zzz-session.jsonl")
        val newer = writeSession(directory, "aaa-session.jsonl")
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000))

        assertEquals(newer, strategy.newestSessionFile(directory))
    }

    @Test
    fun anEmptyDirectoryFallsBackToTheLaunchArguments() {
        val strategy = OmpSessionStrategy(root())
        val directory = strategy.sessionDirectory(tab)
        Files.createDirectories(directory)

        assertNull(strategy.newestSessionFile(directory))
        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab, RememberedSession()))
    }

    @Test
    fun aMissingDirectoryFallsBackToTheLaunchArguments() {
        val strategy = OmpSessionStrategy(root())

        assertNull(strategy.newestSessionFile(strategy.sessionDirectory(tab)))
        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab, RememberedSession()))
    }

    @Test
    fun onlyJsonlFilesDirectlyInTheDirectoryCount() {
        val strategy = OmpSessionStrategy(root())
        val directory = strategy.sessionDirectory(tab)
        writeSession(directory, "2026-09-07T21-15-03-123Z_019a4f3c.json")
        writeSession(directory.resolve("2026-09-07T21-15-03-123Z_019a4f3c"), "SubAgent.jsonl")

        assertNull(strategy.newestSessionFile(directory))
    }

    @Test
    fun theWrappedRestoreCommandPassesTheSessionDirectoryAndFileThroughHeadroom() {
        val strategy = OmpSessionStrategy(root())
        val directory = strategy.sessionDirectory(tab)
        val file = writeSession(directory, "2026-09-08T09-02-11-000Z_019a52118c334de1af664e2c3b7e9d58.jsonl")

        val expectedDirectory = ShellWords.quote(directory.toString())
        val expectedFile = ShellWords.quote(file.toString())

        assertEquals(
            "headroom wrap omp --no-serena -- --session-dir $expectedDirectory --resume $expectedFile",
            AgentCommand.parse("headroom wrap omp --no-serena")
                .withArguments(strategy.restoreArguments(tab, RememberedSession())),
        )
    }

    @Test
    fun theOmpRootComesFromPiCodingAgentDirWhenItIsSet() {
        val home = temporaryFolder.newFolder("home").toPath()
        val override = temporaryFolder.newFolder("pi-agent").toPath()
        val stateDirectory = temporaryFolder.newFolder("state").toPath()

        assertEquals(
            home.resolve(".omp").resolve("agent"),
            AgentSessionEnvironment(home, stateDirectory).ompRoot,
        )
        assertEquals(
            override,
            AgentSessionEnvironment(home, stateDirectory, piCodingAgentDir = override.toString()).ompRoot,
        )
    }

    private fun root(): Path = temporaryFolder.root.toPath().resolve("omp-agent")

    private fun writeSession(directory: Path, name: String): Path {
        Files.createDirectories(directory)
        val file = directory.resolve(name)
        Files.writeString(file, "{}\n")
        return file
    }
}
