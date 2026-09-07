package com.github.atm1020.tuilaunch.resume

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

class OmpSessionStrategy(private val root: Path) : AgentSessionStrategy {
    override fun launchArguments(tab: TabIdentity): List<String> =
        listOf(SESSION_DIR_FLAG, sessionDirectory(tab).toString())

    override fun restoreArguments(tab: TabIdentity, remembered: RememberedSession): List<String> {
        val directory = sessionDirectory(tab)
        val newest = newestSessionFile(directory) ?: return launchArguments(tab)
        return listOf(SESSION_DIR_FLAG, directory.toString(), RESUME_FLAG, newest.toAbsolutePath().toString())
    }

    fun sessionDirectory(tab: TabIdentity): Path = root
        .resolve(SESSIONS_DIRECTORY)
        .resolve("--tuilaunch-${tab.projectHash}-${tab.tabUuid}--")

    fun newestSessionFile(directory: Path): Path? {
        val files = sessionFilesIn(directory)
        if (files.isEmpty()) return null
        val everyNameStartsWithATimestamp = files.all { TIMESTAMP_PREFIX.containsMatchIn(it.fileName.toString()) }
        return if (everyNameStartsWithATimestamp) {
            files.maxByOrNull { it.fileName.toString() }
        } else {
            files.maxByOrNull { lastModifiedMillis(it) }
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
        private const val SESSION_FILE_SUFFIX = ".jsonl"
        private val TIMESTAMP_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}[T_-]")
    }
}
