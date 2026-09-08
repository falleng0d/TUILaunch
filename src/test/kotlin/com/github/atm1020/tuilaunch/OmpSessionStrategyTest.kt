package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.OmpSessionStrategy
import com.github.atm1020.tuilaunch.resume.ShellWords
import com.github.atm1020.tuilaunch.resume.TabIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private const val OLDER_SESSION = "2026-09-07T21-15-03-123Z_019a4f3c7b217cd09e553f1b2a6d8c47.jsonl"
private const val NEWEST_SESSION = "2026-09-08T09-02-11-000Z_019a52118c334de1af664e2c3b7e9d58.jsonl"
private const val REPORTED_SESSION_ID = "019a52118c334de1af664e2c3b7e9d58"

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
        val strategy = OmpSessionStrategy(root, bundledDirectory())

        assertEquals(
            root.resolve("sessions").resolve("--tuilaunch-TUILaunch.b2c3d4-$tabUuid--"),
            strategy.sessionDirectory(tab),
        )
    }

    @Test
    fun theBundledExtensionSitsInAnOmpDirectoryOfItsOwn() {
        val bundled = bundledDirectory()
        val strategy = OmpSessionStrategy(root(), bundled)

        assertEquals(
            bundled.resolve("omp").resolve("tuilaunch-follow-session.js"),
            strategy.followSessionExtension(),
        )
    }

    @Test
    fun launchArgumentsPointOmpAtTheTabDirectoryAndTheBundledExtension() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)

        assertEquals(
            listOf(
                "--session-dir",
                strategy.sessionDirectory(tab).toString(),
                "--hook",
                strategy.followSessionExtension().toString(),
            ),
            strategy.launchArguments(tab),
        )
    }

    @Test
    fun prepareLaunchWritesTheBundledExtension() {
        val strategy = newStrategy()

        strategy.prepareLaunch(tab)

        assertArrayEquals(bundledResource(), Files.readAllBytes(strategy.followSessionExtension()))
    }

    @Test
    fun prepareLaunchLeavesAnExtensionThatIsAlreadyCurrentAlone() {
        val strategy = newStrategy()
        val extension = strategy.followSessionExtension()
        Files.createDirectories(extension.parent)
        Files.write(extension, bundledResource())
        val untouched = FileTime.fromMillis(1_000_000)
        Files.setLastModifiedTime(extension, untouched)

        strategy.prepareLaunch(tab)

        assertEquals(untouched, Files.getLastModifiedTime(extension))
    }

    @Test
    fun prepareLaunchRewritesAnExtensionThatDiffers() {
        val strategy = newStrategy()
        val extension = strategy.followSessionExtension()
        Files.createDirectories(extension.parent)
        Files.writeString(extension, "export default function () {}\n")

        strategy.prepareLaunch(tab)

        assertArrayEquals(bundledResource(), Files.readAllBytes(extension))
        assertFalse(Files.exists(extension.resolveSibling("${extension.fileName}.tmp")))
    }

    @Test
    fun theExtensionPinsItsOwnDirectoryAndUsesNoTimer() {
        val extension = String(bundledResource())

        for (expected in listOf(
            """const MARKER = "--tuilaunch-";""",
            "ownDirectory",
            "join(ownDirectory, STATE_FILE)",
            "renameSync(staging, target)",
        )) {
            assertTrue(expected, extension.contains(expected))
        }
        assertFalse(extension, extension.contains("setTimeout"))
        assertFalse(extension, extension.contains("setInterval"))
    }

    @Test
    fun anExtensionThatCouldNotBeWrittenIsNotPutOnTheCommandLine() {
        val strategy = newStrategy()
        val directoryArguments = listOf("--session-dir", strategy.sessionDirectory(tab).toString())

        assertEquals(directoryArguments, strategy.launchArguments(tab))
        assertEquals(directoryArguments, strategy.restoreArguments(tab))
    }

    @Test
    fun restoreResumesTheSessionTheExtensionReported() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        val directory = strategy.sessionDirectory(tab)
        val reported = writeSession(directory, OLDER_SESSION, modifiedAt = 1_000_000)
        writeSession(directory, NEWEST_SESSION, modifiedAt = 2_000_000)
        writeActiveSession(strategy, reported)

        assertEquals(
            sessionArguments(strategy) + listOf("--resume", reported.toString()),
            strategy.restoreArguments(tab),
        )
        assertEquals(REPORTED_SESSION_ID, strategy.readSessionId(strategy.activeSessionFile(tab)))
    }

    @Test
    fun aReportedSessionThatIsGoneFallsBackToTheNewestFile() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        val directory = strategy.sessionDirectory(tab)
        val newest = writeSession(directory, NEWEST_SESSION, modifiedAt = 2_000_000)
        writeActiveSession(strategy, directory.resolve("2026-09-01T00-00-00-000Z_gone.jsonl"))

        assertNull(strategy.readActiveSession(strategy.activeSessionFile(tab)))
        assertNull(strategy.readSessionId(strategy.activeSessionFile(tab)))
        assertEquals(
            sessionArguments(strategy) + listOf("--resume", newest.toString()),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun aReportThatDoesNotParseFallsBackToTheNewestFile() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        val directory = strategy.sessionDirectory(tab)
        val newest = writeSession(directory, NEWEST_SESSION, modifiedAt = 2_000_000)
        write(strategy.activeSessionFile(tab), """{"sessionFile":"""")

        assertNull(strategy.readActiveSession(strategy.activeSessionFile(tab)))
        assertEquals(
            sessionArguments(strategy) + listOf("--resume", newest.toString()),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun aReportWithoutASessionFileFallsBackToTheNewestFile() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        val directory = strategy.sessionDirectory(tab)
        val newest = writeSession(directory, NEWEST_SESSION, modifiedAt = 2_000_000)
        write(strategy.activeSessionFile(tab), """{"sessionId":"$REPORTED_SESSION_ID"}""")

        assertNull(strategy.readSessionId(strategy.activeSessionFile(tab)))
        assertEquals(
            sessionArguments(strategy) + listOf("--resume", newest.toString()),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun theFallbackTakesTheMostRecentlyModifiedFile() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        val directory = strategy.sessionDirectory(tab)
        writeSession(directory, NEWEST_SESSION, modifiedAt = 1_000_000)
        val resumedAgain = writeSession(directory, OLDER_SESSION, modifiedAt = 2_000_000)

        assertEquals(resumedAgain, strategy.newestSessionFile(directory))
    }

    @Test
    fun filesModifiedAtTheSameTimeAreOrderedByName() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        val directory = strategy.sessionDirectory(tab)
        writeSession(directory, OLDER_SESSION, modifiedAt = 1_000_000)
        val newest = writeSession(directory, NEWEST_SESSION, modifiedAt = 1_000_000)

        assertEquals(newest, strategy.newestSessionFile(directory))
    }

    @Test
    fun anEmptyDirectoryFallsBackToTheLaunchArguments() {
        val strategy = newStrategy()
        val directory = strategy.sessionDirectory(tab)
        Files.createDirectories(directory)

        assertNull(strategy.newestSessionFile(directory))
        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab))
    }

    @Test
    fun aMissingDirectoryFallsBackToTheLaunchArguments() {
        val strategy = newStrategy()

        assertNull(strategy.newestSessionFile(strategy.sessionDirectory(tab)))
        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab))
    }

    @Test
    fun onlyJsonlFilesDirectlyInTheDirectoryCount() {
        val strategy = newStrategy()
        val directory = strategy.sessionDirectory(tab)
        writeSession(directory, "2026-09-07T21-15-03-123Z_019a4f3c.json")
        writeSession(directory.resolve("2026-09-07T21-15-03-123Z_019a4f3c"), "SubAgent.jsonl")

        assertNull(strategy.newestSessionFile(directory))
    }

    @Test
    fun aCommandWithATrustedExtensionGetsNoHook() {
        val strategy = OmpSessionStrategy(root(), bundledDirectory(), hookAllowed = false)
        val directory = strategy.sessionDirectory(tab)
        val reported = writeSession(directory, NEWEST_SESSION, modifiedAt = 2_000_000)
        writeActiveSession(strategy, reported)

        val directoryArguments = listOf("--session-dir", directory.toString())
        assertEquals(directoryArguments, strategy.launchArguments(tab))
        assertEquals(
            directoryArguments + listOf("--resume", reported.toString()),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun theWrappedRestoreCommandPassesEveryPathThroughHeadroom() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        val directory = strategy.sessionDirectory(tab)
        val file = writeSession(directory, NEWEST_SESSION, modifiedAt = 2_000_000)

        val expectedDirectory = ShellWords.quote(directory.toString())
        val expectedExtension = ShellWords.quote(strategy.followSessionExtension().toString())
        val expectedFile = ShellWords.quote(file.toString())

        assertEquals(
            "headroom wrap omp --no-serena -- --session-dir $expectedDirectory " +
                "--hook $expectedExtension --resume $expectedFile",
            AgentCommand.parse("headroom wrap omp --no-serena")
                .withArguments(strategy.restoreArguments(tab)),
        )
    }

    @Test
    fun theOmpRootComesFromPiCodingAgentDirWhenItIsSet() {
        val home = temporaryFolder.newFolder("home").toPath()
        val override = temporaryFolder.newFolder("pi-agent").toPath()
        val stateDirectory = temporaryFolder.newFolder("state").toPath()
        val bundled = temporaryFolder.newFolder("bundled").toPath()

        assertEquals(
            home.resolve(".omp").resolve("agent"),
            AgentSessionEnvironment(home, stateDirectory, bundled).ompRoot,
        )
        assertEquals(
            override,
            AgentSessionEnvironment(
                home,
                stateDirectory,
                bundled,
                piCodingAgentDir = override.toString(),
            ).ompRoot,
        )
    }

    private fun newStrategy(): OmpSessionStrategy = OmpSessionStrategy(root(), bundledDirectory())

    private fun root(): Path = temporaryFolder.root.toPath().resolve("omp-agent")

    private fun bundledDirectory(): Path = temporaryFolder.root.toPath().resolve("integrations")

    private fun sessionArguments(strategy: OmpSessionStrategy): List<String> = listOf(
        "--session-dir",
        strategy.sessionDirectory(tab).toString(),
        "--hook",
        strategy.followSessionExtension().toString(),
    )

    private fun bundledResource(): ByteArray {
        val stream = requireNotNull(
            OmpSessionStrategy::class.java.getResourceAsStream(OmpSessionStrategy.FOLLOW_SESSION_RESOURCE)
        )
        return stream.use { it.readBytes() }
    }

    private fun writeActiveSession(strategy: OmpSessionStrategy, sessionFile: Path) {
        write(
            strategy.activeSessionFile(tab),
            """{"sessionFile":"$sessionFile","sessionId":"$REPORTED_SESSION_ID",""" +
                """"updatedAt":"2026-09-08T09:02:11.000Z"}""" + "\n",
        )
    }

    private fun writeSession(directory: Path, name: String, modifiedAt: Long? = null): Path {
        val file = directory.resolve(name)
        write(file, "{}\n")
        if (modifiedAt != null) Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAt))
        return file
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
