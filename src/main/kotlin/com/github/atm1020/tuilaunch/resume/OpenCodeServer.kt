package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.serviceContainer.NonInjectable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

interface OpenCodeServer {
    suspend fun acquire(tabUuid: String, command: AgentCommand): String?

    fun release(tabUuid: String)
}

interface OpenCodeServerProcess {
    val pid: Long?

    val isTerminated: Boolean

    fun onTerminated(listener: () -> Unit)

    fun destroy()

    fun kill()
}

fun interface OpenCodeServerProcessFactory {
    fun start(
        tokens: List<String>,
        environment: Map<String, String>,
        workingDirectory: String?,
    ): OpenCodeServerProcess
}

interface OpenCodeHealthProbe : AutoCloseable {
    suspend fun answers(address: String): Boolean

    override fun close() {
    }
}

fun allocateFreePort(): Int = ServerSocket(0).use { it.localPort }

fun endTheProcessWithPid(pid: Long) {
    val handle = ProcessHandle.of(pid).orElse(null) ?: return
    if (!handle.isAlive || !looksLikeAnOpenCodeServer(handle)) return
    logger<OpenCodeServerService>().info("Ending the OpenCode server $pid an earlier IDE run left behind")
    handle.destroy()
}

fun aProcessIsRunning(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

private fun looksLikeAnOpenCodeServer(handle: ProcessHandle): Boolean {
    val info = handle.info()
    val description = info.commandLine().orElse(null) ?: info.command().orElse(null) ?: return false
    return description.contains(AgentCliKind.OPENCODE.programName, ignoreCase = true)
}

@Service(Service.Level.PROJECT)
class OpenCodeServerService @NonInjectable internal constructor(
    private val scope: CoroutineScope,
    private val processes: OpenCodeServerProcessFactory,
    private val health: OpenCodeHealthProbe,
    private val freePort: () -> Int,
    private val endTheServerWithPid: (Long) -> Unit,
    private val theProcessIsRunning: (Long) -> Boolean,
    private val idePid: Long,
    private val stateFile: Path,
    private val workingDirectory: String?,
    private val restartDelaysMs: List<Long>,
    private val pollIntervalMs: Long,
    private val startupTimeoutMs: Long,
    private val stopGraceMs: Long,
    private val releaseGraceMs: Long,
) : OpenCodeServer {

    constructor(project: Project, scope: CoroutineScope) : this(
        scope = scope,
        processes = CommandLineOpenCodeServerProcesses,
        health = HttpOpenCodeHealthProbe(),
        freePort = ::allocateFreePort,
        endTheServerWithPid = ::endTheProcessWithPid,
        theProcessIsRunning = ::aProcessIsRunning,
        idePid = ProcessHandle.current().pid(),
        stateFile = AgentSessionEnvironment.stateDirectoryFor(project.locationHash)
            .resolve(SERVER_STATE_FILE_NAME),
        workingDirectory = project.basePath,
        restartDelaysMs = DEFAULT_RESTART_DELAYS_MS,
        pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
        startupTimeoutMs = DEFAULT_STARTUP_TIMEOUT_MS,
        stopGraceMs = DEFAULT_STOP_GRACE_MS,
        releaseGraceMs = DEFAULT_RELEASE_GRACE_MS,
    )

    private class RunningServer(val port: Int, val process: OpenCodeServerProcess)

    private val transitions = Mutex()
    private val attachedTabs = mutableSetOf<String>()

    @Volatile
    private var running: RunningServer? = null
    private var port: Int? = null
    private var serverCommand: AgentCommand? = null
    private var restartAttempts = 0
    private var aServerFromAnEarlierRunWasHandled = false

    init {
        scope.coroutineContext.job.invokeOnCompletion { endEverything() }
    }

    override suspend fun acquire(tabUuid: String, command: AgentCommand): String? = transitions.withLock {
        serverCommand = command
        restartAttempts = 0
        attachedTabs.add(tabUuid)
        val address = addressOfTheRunningServer()
        if (address == null) attachedTabs.remove(tabUuid)
        address
    }

    override fun release(tabUuid: String) {
        scope.launch {
            val theLastTabIsGone = transitions.withLock {
                if (!attachedTabs.remove(tabUuid)) return@launch
                attachedTabs.isEmpty()
            }
            if (!theLastTabIsGone) return@launch
            delay(releaseGraceMs)
            transitions.withLock {
                if (attachedTabs.isEmpty()) stopTheServer()
            }
        }
    }

    private suspend fun addressOfTheRunningServer(): String? {
        val current = running
        if (current != null && !current.process.isTerminated) return addressOf(current.port)
        return startTheServer()?.let { addressOf(it.port) }
    }

    private suspend fun startTheServer(): RunningServer? {
        val command = serverCommand ?: return null
        killAServerLeftByAnEarlierRun()
        val port = this.port ?: freePort().also { this.port = it }
        val tokens = ShellWords.split(command.withArguments(serveArguments(port)))
        val assignments = leadingAssignmentsIn(tokens)
        val program = tokens.drop(assignments.size)
        if (program.isEmpty()) {
            thisLogger().warn("The OpenCode command '${command.command}' names no program to start a server with")
            return null
        }
        val process = try {
            withContext(Dispatchers.IO) { processes.start(program, environmentIn(assignments), workingDirectory) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            thisLogger().warn("Could not start '${program.joinToString(" ")}'", failure)
            return null
        }
        val started = RunningServer(port, process)
        running = started
        process.onTerminated { restartAfterACrash(started) }
        rememberTheRunningServer(process.pid, port)
        if (process.isTerminated) {
            thisLogger().warn("The OpenCode server on port $port ended before it could answer")
            stopTheServer()
            return null
        }
        if (!theServerAnswers(started)) {
            thisLogger().warn("OpenCode did not answer on ${addressOf(port)} within $startupTimeoutMs ms")
            stopTheServer()
            return null
        }
        thisLogger().info("OpenCode tabs of this project attach to ${addressOf(port)}")
        return started
    }

    private suspend fun theServerAnswers(started: RunningServer): Boolean {
        val address = addressOf(started.port)
        return withTimeoutOrNull(startupTimeoutMs) {
            while (!answersHealth(address)) {
                if (started.process.isTerminated) return@withTimeoutOrNull false
                delay(pollIntervalMs)
            }
            true
        } == true
    }

    private suspend fun answersHealth(address: String): Boolean = try {
        health.answers(address)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    private fun restartAfterACrash(crashed: RunningServer) {
        scope.launch {
            val pause = transitions.withLock {
                if (running !== crashed) return@launch
                running = null
                if (attachedTabs.isEmpty()) {
                    stopTheServer()
                    return@launch
                }
                val next = restartDelaysMs.getOrNull(restartAttempts)
                if (next == null) {
                    thisLogger().warn("The OpenCode server stopped once too often; its tabs stay without a server")
                    stopTheServer()
                    return@launch
                }
                restartAttempts++
                thisLogger().warn("The OpenCode server on port ${crashed.port} stopped; starting it again")
                next
            }
            delay(pause)
            transitions.withLock {
                if (running == null && attachedTabs.isNotEmpty()) startTheServer()
            }
        }
    }

    private suspend fun stopTheServer() {
        val current = running
        running = null
        port = null
        forgetTheRunningServer()
        if (current == null || current.process.isTerminated) return
        current.process.destroy()
        if (!theProcessEnded(current.process)) current.process.kill()
    }

    private suspend fun theProcessEnded(process: OpenCodeServerProcess): Boolean =
        withTimeoutOrNull(stopGraceMs) {
            while (!process.isTerminated) delay(pollIntervalMs)
            true
        } == true

    private suspend fun killAServerLeftByAnEarlierRun() {
        if (aServerFromAnEarlierRunWasHandled) return
        val record = withContext(Dispatchers.IO) { AgentStateFiles.readJsonObject(stateFile) }
        aServerFromAnEarlierRunWasHandled = true
        if (record == null) return
        val owner = longField(record, IDE_FIELD) ?: return
        if (withContext(Dispatchers.IO) { theProcessIsRunning(owner) }) return
        val pid = longField(record, PID_FIELD) ?: return
        withContext(Dispatchers.IO) { endTheServerWithPid(pid) }
    }

    private fun longField(record: JsonObject, field: String): Long? =
        record.get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private suspend fun rememberTheRunningServer(pid: Long?, port: Int) {
        if (pid == null) return
        val record = JsonObject().apply {
            addProperty(PID_FIELD, pid)
            addProperty(PORT_FIELD, port)
            addProperty(IDE_FIELD, idePid)
        }
        withContext(Dispatchers.IO) {
            try {
                Files.createDirectories(stateFile.parent)
                Files.writeString(stateFile, record.toString())
            } catch (_: IOException) {
            }
        }
    }

    private suspend fun forgetTheRunningServer() {
        AgentStateFiles.delete(stateFile)
    }

    private fun endEverything() {
        running?.process?.destroy()
        running = null
        try {
            health.close()
        } catch (_: Exception) {
        }
    }

    private fun serveArguments(port: Int): List<String> =
        listOf(SERVE_SUBCOMMAND, PORT_FLAG, port.toString(), HOSTNAME_FLAG, LOOPBACK_HOSTNAME)

    private fun leadingAssignmentsIn(tokens: List<String>): List<String> =
        tokens.takeWhile { ASSIGNMENT.containsMatchIn(it) }

    private fun environmentIn(assignments: List<String>): Map<String, String> =
        assignments.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun addressOf(port: Int): String = "http://$LOOPBACK_HOSTNAME:$port"

    companion object {
        const val SERVE_SUBCOMMAND = "serve"
        const val PORT_FLAG = "--port"
        const val HOSTNAME_FLAG = "--hostname"
        const val LOOPBACK_HOSTNAME = "127.0.0.1"
        const val HEALTH_PATH = "/global/health"
        const val SERVER_STATE_FILE_NAME = "opencode-server.json"

        val DEFAULT_RESTART_DELAYS_MS: List<Long> = listOf(1_000L, 4_000L, 16_000L)

        const val DEFAULT_POLL_INTERVAL_MS = 250L
        const val DEFAULT_STARTUP_TIMEOUT_MS = 90_000L
        const val DEFAULT_STOP_GRACE_MS = 2_000L
        const val DEFAULT_RELEASE_GRACE_MS = 3_000L

        private const val PID_FIELD = "pid"
        private const val PORT_FIELD = "port"
        private const val IDE_FIELD = "ide"
        private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")

        fun getInstance(project: Project): OpenCodeServerService = project.service()
    }
}

class HttpOpenCodeHealthProbe : OpenCodeHealthProbe {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    override suspend fun answers(address: String): Boolean = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(address + OpenCodeServerService.HEALTH_PATH))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: IOException) {
            return@withContext false
        }
        response.statusCode() == OK_STATUS && theBodySaysHealthy(response.body())
    }

    override fun close() {
        client.shutdownNow()
    }

    private fun theBodySaysHealthy(body: String): Boolean {
        val healthy = AgentStateFiles.jsonObjectIn(body)?.get(HEALTHY_FIELD) ?: return false
        return healthy.isJsonPrimitive && healthy.asJsonPrimitive.isBoolean && healthy.asBoolean
    }

    private companion object {
        const val OK_STATUS = 200
        const val HEALTHY_FIELD = "healthy"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(1)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

private object CommandLineOpenCodeServerProcesses : OpenCodeServerProcessFactory {
    override fun start(
        tokens: List<String>,
        environment: Map<String, String>,
        workingDirectory: String?,
    ): OpenCodeServerProcess {
        val commandLine = GeneralCommandLine(listOf(executableOf(tokens.first())) + tokens.drop(1))
            .withEnvironment(environment)
        workingDirectory?.let { commandLine.withWorkingDirectory(Path.of(it)) }
        val handler = KillableProcessHandler(commandLine)
        handler.addProcessListener(OpenCodeServerOutput)
        handler.startNotify()
        return ProcessHandlerOpenCodeServer(handler)
    }

    private fun executableOf(program: String): String {
        if (program.contains('/') || program.contains('\\')) return program
        return PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(program)?.absolutePath ?: program
    }
}

private object OpenCodeServerOutput : ProcessListener {
    private const val LISTENING_MARKER = "listening on"

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val text = event.text.trimEnd()
        if (text.isEmpty()) return
        if (text.contains(LISTENING_MARKER, ignoreCase = true)) {
            thisLogger().info("opencode serve: $text")
        } else {
            thisLogger().debug("opencode serve: $text")
        }
    }
}

private class ProcessHandlerOpenCodeServer(
    private val handler: KillableProcessHandler,
) : OpenCodeServerProcess {
    override val pid: Long?
        get() = try {
            handler.process.pid()
        } catch (_: UnsupportedOperationException) {
            null
        }

    override val isTerminated: Boolean
        get() = handler.isProcessTerminated

    override fun onTerminated(listener: () -> Unit) {
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = listener()
        })
    }

    override fun destroy() = handler.destroyProcess()

    override fun kill() = handler.killProcess()
}
