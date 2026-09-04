package com.github.atm1020.tuilaunch.copilot

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Key
import com.intellij.serviceContainer.NonInjectable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

interface CopilotServerSettings {
    val explicitServerPath: String
}

object DefaultCopilotServerSettings : CopilotServerSettings {
    override val explicitServerPath: String = ""
}

interface CopilotServerProcess {
    val standardOutput: InputStream
    val standardInput: OutputStream
    val isTerminated: Boolean

    fun onTerminated(listener: () -> Unit)

    fun destroy()
}

fun interface CopilotServerProcessFactory {
    fun start(executablePath: String): CopilotServerProcess
}

@Service(Service.Level.APP)
class CopilotLanguageServerService @NonInjectable internal constructor(
    private val scope: CoroutineScope,
    private val settings: CopilotServerSettings,
    private val processFactory: CopilotServerProcessFactory,
    private val locator: (String) -> CopilotServerLocation,
    private val serverFactory: (JsonRpcConnection) -> CopilotLanguageServer,
    private val retryDelaysMs: List<Long>,
    private val handshakeTimeoutMs: Long,
) {
    constructor(scope: CoroutineScope) : this(
        scope,
        DefaultCopilotServerSettings,
        StdioCopilotServerProcessFactory,
        { explicitPath -> locateFromInstalledIde(explicitPath) },
        { connection -> CopilotLanguageServer(connection) },
        DEFAULT_RETRY_DELAYS_MS,
        DEFAULT_HANDSHAKE_TIMEOUT_MS,
    )

    private class Session(
        val process: CopilotServerProcess,
        val connection: JsonRpcConnection,
        val server: CopilotLanguageServer,
    )

    private val stateFlow = MutableStateFlow<CopilotServerState>(CopilotServerState.Stopped)
    private val transitions = Mutex()

    @Volatile
    private var session: Session? = null

    private var wanted = false
    private var retryAttempts = 0

    val state: StateFlow<CopilotServerState> = stateFlow.asStateFlow()

    init {
        scope.coroutineContext.job.invokeOnCompletion { session?.process?.destroy() }
    }

    fun server(): CopilotLanguageServer? =
        if (stateFlow.value is CopilotServerState.Ready) session?.server else null

    fun ensureStarted() {
        scope.launch {
            transitions.withLock {
                if (isTerminalFailure(stateFlow.value)) return@withLock
                startIfNeeded()
            }
        }
    }

    fun restart() {
        scope.launch {
            transitions.withLock {
                stopSession()
                retryAttempts = 0
                startIfNeeded()
            }
        }
    }

    fun stop() {
        scope.launch {
            transitions.withLock {
                wanted = false
                stopSession()
                stateFlow.value = CopilotServerState.Stopped
            }
        }
    }

    suspend fun checkStatusNow(): CopilotServerState = transitions.withLock {
        retryAttempts = 0
        startIfNeeded()
        val current = session ?: return@withLock stateFlow.value
        try {
            stateFlow.value = stateFor(current.server.checkStatus(localChecksOnly = false))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            stateFlow.value = CopilotServerState.Failed(describe(failure))
        }
        stateFlow.value
    }

    private suspend fun startIfNeeded() {
        wanted = true
        if (session != null) return

        val location = locator(settings.explicitServerPath)
        if (location is CopilotServerLocation.NotFound) {
            stateFlow.value = CopilotServerState.NotConfigured(location.reason)
            return
        }
        val executablePath = (location as CopilotServerLocation.Found).path.toString()
        stateFlow.value = CopilotServerState.Starting

        val process = try {
            processFactory.start(executablePath)
        } catch (failure: Exception) {
            stateFlow.value = CopilotServerState.Failed(
                "Could not start the GitHub Copilot language server at $executablePath: ${describe(failure)}"
            )
            return
        }

        val connection = JsonRpcConnection(process.standardOutput, process.standardInput, scope)
        val started = Session(process, connection, serverFactory(connection))
        session = started
        process.onTerminated { onProcessTerminated(started) }

        try {
            withTimeout(handshakeTimeoutMs) {
                started.server.initialize()
                started.server.initialized()
                stateFlow.value = stateFor(started.server.checkStatus(localChecksOnly = true))
            }
            retryAttempts = 0
        } catch (timeout: TimeoutCancellationException) {
            thisLogger().warn("The GitHub Copilot language server handshake timed out", timeout)
            discard(started)
            scheduleRetry("The GitHub Copilot language server did not answer within $handshakeTimeoutMs ms")
            return
        } catch (cancellation: CancellationException) {
            discard(started)
            throw cancellation
        } catch (failure: Exception) {
            thisLogger().warn("The GitHub Copilot language server handshake failed", failure)
            discard(started)
            scheduleRetry("The GitHub Copilot language server did not answer: ${describe(failure)}")
            return
        }

        if (process.isTerminated) onProcessTerminated(started)
    }

    private fun onProcessTerminated(terminated: Session) {
        scope.launch {
            transitions.withLock {
                if (session !== terminated) return@withLock
                discard(terminated)
                scheduleRetry("The GitHub Copilot language server process exited")
            }
        }
    }

    private fun discard(unwanted: Session) {
        if (session === unwanted) session = null
        unwanted.connection.close()
        unwanted.process.destroy()
    }

    private fun scheduleRetry(reason: String) {
        scope.launch {
            var pause = 0L
            transitions.withLock {
                if (!wanted) return@launch
                val next = retryDelaysMs.getOrNull(retryAttempts)
                if (next == null) {
                    stateFlow.value = CopilotServerState.Failed(reason)
                    return@launch
                }
                retryAttempts++
                pause = next
                stateFlow.value = CopilotServerState.Starting
            }
            delay(pause)
            transitions.withLock {
                if (wanted && session == null) startIfNeeded()
            }
        }
    }

    private suspend fun stopSession() {
        val running = session ?: return
        session = null
        if (!running.process.isTerminated) {
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                try {
                    running.server.shutdown()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    thisLogger().debug("The GitHub Copilot language server refused to shut down", failure)
                }
                running.server.exit()
                while (!running.process.isTerminated) delay(STOP_POLL_MS)
            }
        }
        running.connection.close()
        running.process.destroy()
    }

    private fun stateFor(status: CopilotStatus): CopilotServerState =
        if (status.isSignedIn) CopilotServerState.Ready(status.user) else CopilotServerState.NotSignedIn

    private fun isTerminalFailure(state: CopilotServerState): Boolean =
        state is CopilotServerState.Failed || state is CopilotServerState.NotConfigured

    private fun describe(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 4_000L, 16_000L)

        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L

        private const val STOP_TIMEOUT_MS = 2_000L
        private const val STOP_POLL_MS = 25L

        fun getInstance(): CopilotLanguageServerService = service()

        fun locateFromInstalledIde(explicitPath: String): CopilotServerLocation =
            CopilotServerLocator.locate(
                explicitPath = explicitPath,
                copilotPluginPath = findCopilotPluginPath(),
                pathLookup = { name ->
                    PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(name)?.toPath()
                },
            )

        private fun findCopilotPluginPath(): Path? =
            sequenceOf(PathManager.getPluginsDir(), PathManager.getBundledPluginsDir())
                .flatMap { root -> childDirectories(root) }
                .firstOrNull { Files.isDirectory(it.resolve(CopilotServerLocator.COPILOT_AGENT_DIRECTORY)) }

        private fun childDirectories(root: Path): Sequence<Path> =
            runCatching {
                Files.list(root).use { children -> children.filter { Files.isDirectory(it) }.toList() }
            }.getOrDefault(emptyList()).asSequence()
    }
}

private object StdioCopilotServerProcessFactory : CopilotServerProcessFactory {
    override fun start(executablePath: String): CopilotServerProcess {
        val handler = StdioServerProcessHandler(GeneralCommandLine(executablePath, "--stdio"))
        handler.addProcessListener(StderrLogger)
        handler.startNotify()
        return ProcessHandlerServerProcess(handler)
    }
}

private class StdioServerProcessHandler(commandLine: GeneralCommandLine) : KillableProcessHandler(commandLine) {
    override fun createProcessOutReader(): Reader = Reader.nullReader()
}

private object StderrLogger : ProcessListener {
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (outputType == ProcessOutputTypes.STDERR) {
            thisLogger().debug("copilot-language-server: ${event.text.trimEnd()}")
        }
    }
}

private class ProcessHandlerServerProcess(private val handler: KillableProcessHandler) : CopilotServerProcess {
    override val standardOutput: InputStream
        get() = handler.process.inputStream

    override val standardInput: OutputStream
        get() = handler.processInput

    override val isTerminated: Boolean
        get() = handler.isProcessTerminated

    override fun onTerminated(listener: () -> Unit) {
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = listener()
        })
    }

    override fun destroy() = handler.destroyProcess()
}
