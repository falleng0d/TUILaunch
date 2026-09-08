package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.streams.asSequence

class OmpSessionStrategy(
    private val root: Path,
    private val bundledDirectory: Path,
    private val hookAllowed: Boolean = true,
) : AgentSessionStrategy {
    override fun prepareLaunch(tab: TabIdentity) {
        if (!hookAllowed) return
        BundledIntegrationFiles.ensure(FOLLOW_SESSION_RESOURCE, followSessionExtension())
    }

    override fun launchArguments(tab: TabIdentity): List<String> = sessionArguments(tab)

    override fun restoreArguments(tab: TabIdentity): List<String> {
        val reported = readActiveSession(activeSessionFile(tab))
        val session = reported ?: newestSessionFile(sessionDirectory(tab)) ?: return launchArguments(tab)
        return sessionArguments(tab) + listOf(RESUME_FLAG, session.toAbsolutePath().toString())
    }

    fun sessionDirectory(tab: TabIdentity): Path = root
        .resolve(SESSIONS_DIRECTORY)
        .resolve("--tuilaunch-${tab.projectHash}-${tab.tabUuid}--")

    fun followSessionExtension(): Path = bundledDirectory
        .resolve(BUNDLED_SUBDIRECTORY)
        .resolve(FOLLOW_SESSION_FILE_NAME)

    fun activeSessionFile(tab: TabIdentity): Path = sessionDirectory(tab).resolve(ACTIVE_SESSION_FILE_NAME)

    fun readActiveSession(activeSessionFile: Path): Path? {
        val record = AgentStateFiles.readJsonObject(activeSessionFile) ?: return null
        return reportedSessionFile(record)
    }

    fun readSessionId(activeSessionFile: Path): String? {
        val record = AgentStateFiles.readJsonObject(activeSessionFile) ?: return null
        if (reportedSessionFile(record) == null) return null
        return record.nonBlankString(SESSION_ID_FIELD)
    }

    fun newestSessionFile(directory: Path): Path? = sessionFilesIn(directory)
        .maxWithOrNull(compareBy({ lastModifiedMillis(it) }, { it.fileName.toString() }))

    private fun sessionArguments(tab: TabIdentity): List<String> {
        val directory = listOf(SESSION_DIR_FLAG, sessionDirectory(tab).toAbsolutePath().toString())
        val extension = followSessionExtension()
        if (!hookAllowed || !BundledIntegrationFiles.areOnDisk(extension)) return directory
        return directory + listOf(HOOK_FLAG, extension.toAbsolutePath().toString())
    }

    private fun reportedSessionFile(record: JsonObject): Path? {
        val reported = record.nonBlankString(SESSION_FILE_FIELD) ?: return null
        return try {
            Path.of(reported).takeIf { Files.isRegularFile(it) }
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun sessionFilesIn(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return try {
            Files.list(directory).use { entries ->
                entries.asSequence()
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(SESSION_FILE_SUFFIX) }
                    .toList()
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun lastModifiedMillis(file: Path): Long = try {
        Files.getLastModifiedTime(file).toMillis()
    } catch (_: IOException) {
        0L
    }

    companion object {
        const val SESSIONS_DIRECTORY = "sessions"
        const val SESSION_DIR_FLAG = "--session-dir"
        const val RESUME_FLAG = "--resume"
        const val HOOK_FLAG = "--hook"
        const val BUNDLED_SUBDIRECTORY = "omp"
        const val FOLLOW_SESSION_FILE_NAME = "tuilaunch-follow-session.js"
        const val FOLLOW_SESSION_RESOURCE = "/integrations/omp/$FOLLOW_SESSION_FILE_NAME"
        const val ACTIVE_SESSION_FILE_NAME = "tuilaunch-active.json"
        private const val SESSION_FILE_SUFFIX = ".jsonl"
        private const val SESSION_FILE_FIELD = "sessionFile"
        private const val SESSION_ID_FIELD = "sessionId"
    }
}
