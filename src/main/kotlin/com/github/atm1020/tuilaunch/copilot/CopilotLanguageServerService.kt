package com.github.atm1020.tuilaunch.copilot

import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Key
import com.intellij.serviceContainer.NonInjectable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

interface CopilotServerSettings {
    val explicitServerPath: String

    val copilotIsTheCompletionSource: Boolean
}

object PersistentCopilotServerSettings : CopilotServerSettings {
    override val explicitServerPath: String
        get() = TuiLauncherSettings.getInstance().state.copilotLanguageServerPath

    override val copilotIsTheCompletionSource: Boolean
        get() = TuiLauncherSettings.getInstance().state.promptBoxCompletionSource == PromptBoxCompletionSource.COPILOT
}

interface CopilotServerControl {
    fun locate(explicitPath: String): CopilotServerLocation

    fun checkStatus(explicitPath: String, onResult: (CopilotServerState) -> Unit)

    fun ensureStarted()

    fun restart()

    fun stop()
}

object InstalledCopilotServerControl : CopilotServerControl {
    override fun locate(explicitPath: String): CopilotServerLocation = installedService().locate(explicitPath)

    override fun checkStatus(explicitPath: String, onResult: (CopilotServerState) -> Unit) =
        installedService().checkStatus(explicitPath, onResult)

    override fun ensureStarted() = installedService().ensureStarted()

    override fun restart() = installedService().restart()

    override fun stop() = installedService().stop()

    private fun installedService(): CopilotLanguageServerService = CopilotLanguageServerService.getInstance()
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
) : CopilotServerControl, CopilotCompletionBackend {
    constructor(scope: CoroutineScope) : this(
        scope,
        PersistentCopilotServerSettings,
        StdioCopilotServerProcessFactory,
        { explicitPath -> locateFromInstalledIde(explicitPath) },
        { connection -> CopilotLanguageServer(connection) },
        DEFAULT_RETRY_DELAYS_MS,
        DEFAULT_HANDSHAKE_TIMEOUT_MS,
    )

    private class Session(
        val executablePath: String,
        val process: CopilotServerProcess,
        val connection: JsonRpcConnection,
        val server: CopilotLanguageServer,
    ) {
        @Volatile
        var teardown: Job? = null

        @Volatile
        var statusWatch: Job? = null

        fun cancelWatchers() {
            statusWatch?.cancel()
            teardown?.cancel()
        }
    }

    private val stateFlow = MutableStateFlow<CopilotServerState>(CopilotServerState.Stopped)
    private val transitions = Mutex()

    @Volatile
    private var session: Session? = null

    private var wanted = false
    private var retryAttempts = 0
    private val statusRecheckRunning = AtomicBoolean(false)

    val state: StateFlow<CopilotServerState> = stateFlow.asStateFlow()

    override val serverState: CopilotServerState
        get() = stateFlow.value

    init {
        scope.coroutineContext.job.invokeOnCompletion { session?.process?.destroy() }
    }

    fun server(): CopilotLanguageServer? =
        if (stateFlow.value is CopilotServerState.Ready) session?.server else null

    override fun completionServer(): CopilotCompletionServer? = server()

    override fun launchFollowUp(work: suspend (CopilotCompletionServer) -> Unit) {
        scope.launch {
            try {
                server()?.let { work(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                thisLogger().debug("A GitHub Copilot follow-up command failed", failure)
            }
        }
    }

    override fun ensureStarted() {
        scope.launch {
            transitions.withLock {
                if (isTerminalFailure(stateFlow.value)) return@withLock
                startIfNeeded()
            }
        }
    }

    override fun locate(explicitPath: String): CopilotServerLocation = locator(explicitPath)

    override fun restart() {
        scope.launch {
            transitions.withLock {
                stopSession()
                retryAttempts = 0
                startIfNeeded()
            }
        }
    }

    override fun stop() {
        scope.launch {
            transitions.withLock {
                wanted = false
                stopSession()
                stateFlow.value = CopilotServerState.Stopped
            }
        }
    }

    override fun checkStatus(explicitPath: String, onResult: (CopilotServerState) -> Unit) {
        scope.launch {
            val result = try {
                checkStatusNow(explicitPath)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                CopilotServerState.Failed(describe(failure))
            }
            ApplicationManager.getApplication().invokeLater({ onResult(result) }, ModalityState.any())
        }
    }

    suspend fun checkStatusNow(explicitPath: String): CopilotServerState {
        val location = locateServer(explicitPath)
        if (location is CopilotServerLocation.NotFound) {
            return CopilotServerState.NotConfigured(location.reason)
        }
        val executablePath = (location as CopilotServerLocation.Found).path.toString()
        val running = transitions.withLock {
            session?.takeUnless { it.executablePath != executablePath || it.process.isTerminated }
        }
        return running?.let { statusOf(it.server) } ?: statusOfSingleUseSession(executablePath)
    }

    private suspend fun statusOf(server: CopilotLanguageServer): CopilotServerState = try {
        stateFor(server.checkStatus(localChecksOnly = false))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        CopilotServerState.Failed(describe(failure))
    }

    private suspend fun statusOfSingleUseSession(executablePath: String): CopilotServerState {
        val process = try {
            startProcess(executablePath)
        } catch (failure: Exception) {
            return CopilotServerState.Failed(startFailureReason(executablePath, failure))
        }
        val connection = JsonRpcConnection(process.standardOutput, process.standardInput, scope)
        return try {
            val server = serverFactory(connection)
            withTimeout(handshakeTimeoutMs) {
                server.initialize()
                server.initialized()
                stateFor(server.checkStatus(localChecksOnly = false))
            }
        } catch (timeout: TimeoutCancellationException) {
            thisLogger().warn("The GitHub Copilot language server status check timed out", timeout)
            CopilotServerState.Failed(handshakeTimeoutReason())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            CopilotServerState.Failed("The GitHub Copilot language server did not answer: ${describe(failure)}")
        } finally {
            connection.close()
            process.destroy()
        }
    }

    private suspend fun startIfNeeded() {
        if (!settings.copilotIsTheCompletionSource) return
        wanted = true
        if (session != null) return

        val location = locateServer(settings.explicitServerPath)
        if (location is CopilotServerLocation.NotFound) {
            stateFlow.value = CopilotServerState.NotConfigured(location.reason)
            return
        }
        val executablePath = (location as CopilotServerLocation.Found).path.toString()
        stateFlow.value = CopilotServerState.Starting

        val process = try {
            startProcess(executablePath)
        } catch (failure: Exception) {
            stateFlow.value = CopilotServerState.Failed(startFailureReason(executablePath, failure))
            return
        }

        val connection = JsonRpcConnection(process.standardOutput, process.standardInput, scope)
        val started = Session(executablePath, process, connection, serverFactory(connection))
        session = started
        started.teardown = tearDownWhenTheScopeIsCancelled(started)
        started.statusWatch = followStatusChanges(started)
        process.onTerminated { endSession(started, PROCESS_EXITED_REASON) }
        connection.onClosed { endSession(started, CONNECTION_CLOSED_REASON) }

        try {
            withTimeout(handshakeTimeoutMs) {
                started.server.initialize()
                started.server.initialized()
                stateFlow.value = stateFor(started.server.checkStatus(localChecksOnly = false))
            }
            retryAttempts = 0
        } catch (timeout: TimeoutCancellationException) {
            thisLogger().warn("The GitHub Copilot language server handshake timed out", timeout)
            discard(started)
            scheduleRetry(handshakeTimeoutReason())
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

        if (process.isTerminated) endSession(started, PROCESS_EXITED_REASON)
    }

    private suspend fun locateServer(explicitPath: String): CopilotServerLocation =
        withContext(Dispatchers.IO) { locator(explicitPath) }

    private suspend fun startProcess(executablePath: String): CopilotServerProcess =
        withContext(Dispatchers.IO) { processFactory.start(executablePath) }

    private fun endSession(ended: Session, reason: String) {
        scope.launch {
            transitions.withLock {
                if (session !== ended) return@withLock
                discard(ended)
                scheduleRetry(reason)
            }
        }
    }

    private fun tearDownWhenTheScopeIsCancelled(started: Session): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                started.process.destroy()
                started.connection.close()
            }
        }

    private fun followStatusChanges(started: Session): Job = scope.launch {
        started.server.statusChanges.collect { change -> onStatusChange(started, change) }
    }

    private suspend fun onStatusChange(source: Session, change: CopilotStatusChange) {
        if (change.busy) return
        when {
            change.kind.equals(ERROR_STATUS_KIND, ignoreCase = true) -> reportSignInProblem(source, change.message)
            change.kind.equals(NORMAL_STATUS_KIND, ignoreCase = true) -> recheckStatusOf(source)
        }
    }

    private suspend fun reportSignInProblem(source: Session, message: String?) {
        if (message == null || !mentionsSigningIn(message)) return
        transitions.withLock {
            if (session === source) stateFlow.value = CopilotServerState.NotSignedIn(message)
        }
    }

    private suspend fun recheckStatusOf(source: Session) {
        if (!statusRecheckRunning.compareAndSet(false, true)) return
        try {
            if (session !== source || stateFlow.value !is CopilotServerState.NotSignedIn) return
            val rechecked = statusOf(source.server)
            if (rechecked is CopilotServerState.Failed) return
            transitions.withLock {
                if (session === source && stateFlow.value is CopilotServerState.NotSignedIn) {
                    stateFlow.value = rechecked
                }
            }
        } finally {
            statusRecheckRunning.set(false)
        }
    }

    private fun mentionsSigningIn(message: String): Boolean {
        val text = message.lowercase()
        return SIGN_IN_HINTS.any { it in text }
    }

    private fun discard(unwanted: Session) {
        if (session === unwanted) session = null
        unwanted.cancelWatchers()
        unwanted.process.destroy()
        unwanted.connection.close()
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
        running.cancelWatchers()
        running.connection.close()
        running.process.destroy()
    }

    private fun stateFor(status: CopilotStatus): CopilotServerState =
        if (status.isSignedIn) {
            CopilotServerState.Ready(status.user)
        } else {
            CopilotServerState.NotSignedIn(status.status)
        }

    private fun isTerminalFailure(state: CopilotServerState): Boolean =
        state is CopilotServerState.Failed || state is CopilotServerState.NotConfigured

    private fun describe(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName

    private fun startFailureReason(executablePath: String, failure: Throwable): String =
        "Could not start the GitHub Copilot language server at $executablePath: ${describe(failure)}"

    private fun handshakeTimeoutReason(): String =
        "The GitHub Copilot language server did not answer within $handshakeTimeoutMs ms"

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 4_000L, 16_000L)

        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L

        private const val STOP_TIMEOUT_MS = 2_000L
        private const val STOP_POLL_MS = 25L
        private const val NORMAL_STATUS_KIND = "Normal"
        private const val ERROR_STATUS_KIND = "Error"
        private const val PROCESS_EXITED_REASON = "The GitHub Copilot language server process exited"
        private const val CONNECTION_CLOSED_REASON = "The GitHub Copilot language server connection closed"

        private val SIGN_IN_HINTS = listOf(
            "sign in",
            "signed in",
            "sign-in",
            "signin",
            "authenticate",
            "authentication",
            "not authorized",
        )

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
