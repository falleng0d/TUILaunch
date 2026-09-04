package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.CopilotEditorInfo
import com.github.atm1020.tuilaunch.copilot.CopilotLanguageServer
import com.github.atm1020.tuilaunch.copilot.CopilotLanguageServerService
import com.github.atm1020.tuilaunch.copilot.CopilotServerLocation
import com.github.atm1020.tuilaunch.copilot.CopilotServerProcess
import com.github.atm1020.tuilaunch.copilot.CopilotServerSettings
import com.github.atm1020.tuilaunch.copilot.CopilotServerSource
import com.github.atm1020.tuilaunch.copilot.CopilotServerState
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CopilotLanguageServerServiceTest {

    private lateinit var scope: CoroutineScope
    private val startedProcesses = CopyOnWriteArrayList<FakeServerProcess>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        startedProcesses.forEach { it.terminate() }
        scope.cancel()
    }

    @Test
    fun `the state stays starting until the server answers`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)

        service.ensureStarted()

        assertEquals("initialize", process.peer.read()["method"].asString)
        assertEquals(CopilotServerState.Starting, awaitState(service) { it is CopilotServerState.Starting })
    }

    @Test
    fun `a signed in server becomes ready with its user`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("OK", "falleng0d"))

        service.ensureStarted()

        val ready = awaitState(service) { it is CopilotServerState.Ready }
        assertEquals(CopilotServerState.Ready("falleng0d"), ready)
        assertNotNull(service.server())
        assertTrue(process.methods.contains("initialized"))
        assertTrue(process.methods.contains("checkStatus"))
    }

    @Test
    fun `the startup handshake asks the server to verify the token over the network`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("OK", "falleng0d"))

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.Ready }

        assertFalse(checkStatusOptionsOf(process)["localChecksOnly"].asBoolean)
    }

    @Test
    fun `a server without credentials reports the status it answered`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("NotSignedIn", null))

        service.ensureStarted()

        assertEquals(
            CopilotServerState.NotSignedIn("NotSignedIn"),
            awaitState(service) { it is CopilotServerState.NotSignedIn },
        )
        assertNull(service.server())
    }

    @Test
    fun `a locally cached token alone does not make the server ready`() {
        assertEquals(CopilotServerState.NotSignedIn("MaybeOK"), stateAnsweredFor("MaybeOK"))
        assertEquals(CopilotServerState.NotSignedIn("MaybeOk"), stateAnsweredFor("MaybeOk"))
    }

    @Test
    fun `a normal status change rechecks a server that was not signed in`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("NotSignedIn", null), status("OK", "falleng0d"))

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.NotSignedIn }
        process.peer.notify(CopilotLanguageServer.DID_CHANGE_STATUS, statusChange("Normal", null))

        assertEquals(
            CopilotServerState.Ready("falleng0d"),
            awaitState(service) { it is CopilotServerState.Ready },
        )
        assertNotNull(service.server())
    }

    @Test
    fun `an error status change about signing in carries the message the server sent`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("OK", "falleng0d"))

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.Ready }
        process.peer.notify(
            CopilotLanguageServer.DID_CHANGE_STATUS,
            statusChange("Error", "You are not signed into GitHub."),
        )

        assertEquals(
            CopilotServerState.NotSignedIn("You are not signed into GitHub."),
            awaitState(service) { it is CopilotServerState.NotSignedIn },
        )
    }

    @Test
    fun `a normal status change does not recheck a server that is already ready`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("OK", "falleng0d"))

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.Ready }
        process.peer.notify(CopilotLanguageServer.DID_CHANGE_STATUS, statusChange("Normal", null))
        process.peer.notify(
            CopilotLanguageServer.DID_CHANGE_STATUS,
            statusChange("Error", "You are not signed into GitHub."),
        )
        awaitState(service) { it is CopilotServerState.NotSignedIn }

        assertEquals(1, process.methods.count { it == "checkStatus" })
    }

    @Test
    fun `a server that closes immediately fails after the retries`() {
        val service = service(
            retryDelaysMs = listOf(1L, 1L, 1L),
            processFactory = {
                FakeServerProcess().also {
                    startedProcesses += it
                    it.terminate()
                }
            },
        )

        service.ensureStarted()

        val failed = awaitState(service) { it is CopilotServerState.Failed }
        assertTrue((failed as CopilotServerState.Failed).reason.isNotEmpty())
        assertEquals(4, startedProcesses.size)
        assertNull(service.server())
    }

    @Test
    fun `stopping the service asks the server to shut down and exit`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("OK", "falleng0d"))

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.Ready }
        service.stop()

        assertEquals(CopilotServerState.Stopped, awaitState(service) { it is CopilotServerState.Stopped })
        assertTrue(process.methods.contains("shutdown"))
        assertTrue(process.methods.contains("exit"))
        assertTrue(process.isTerminated)
        assertNull(service.server())
    }

    @Test
    fun `cancelling the scope tears down an idle session instead of waiting for the read loop`() {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status("OK", "falleng0d"))

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.Ready }
        scope.cancel()

        awaitScopeCompletion()
        assertTrue(process.isTerminated)
    }

    @Test
    fun `cancelling the scope tears down a session whose handshake is still pending`() {
        val process = FakeServerProcess()
        startedProcesses += process
        val service = service(processFactory = { process }, handshakeTimeoutMs = UNREACHABLE_TIMEOUT_MS)

        service.ensureStarted()
        assertEquals("initialize", process.peer.read()["method"].asString)
        awaitState(service) { it is CopilotServerState.Starting }
        scope.cancel()

        awaitScopeCompletion()
        assertTrue(process.isTerminated)
    }

    @Test
    fun `a missing binary is reported without starting a process`() {
        val service = service(
            locator = { CopilotServerLocation.NotFound("No GitHub Copilot language server found; tried nothing") },
            processFactory = { throw AssertionError("no process may be started") },
        )

        service.ensureStarted()

        val state = awaitState(service) { it is CopilotServerState.NotConfigured }
        assertEquals(
            "No GitHub Copilot language server found; tried nothing",
            (state as CopilotServerState.NotConfigured).reason,
        )
    }

    @Test
    fun `the configured path reaches the locator`() {
        val requestedPaths = CopyOnWriteArrayList<String>()
        val service = service(
            settings = settingsWith("/opt/copilot/copilot-language-server"),
            locator = { explicitPath ->
                requestedPaths += explicitPath
                CopilotServerLocation.NotFound("nothing")
            },
            processFactory = { throw AssertionError("no process may be started") },
        )

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.NotConfigured }

        assertEquals(listOf("/opt/copilot/copilot-language-server"), requestedPaths)
    }

    @Test
    fun `a status check on a missing binary reports that nothing is configured`() {
        val service = service(
            locator = { CopilotServerLocation.NotFound("No GitHub Copilot language server found; tried nothing") },
            processFactory = { throw AssertionError("no process may be started") },
        )

        val state = checkStatus(service, "/opt/copilot/copilot-language-server")

        assertEquals(
            CopilotServerState.NotConfigured("No GitHub Copilot language server found; tried nothing"),
            state,
        )
    }

    @Test
    fun `a status check runs a server of its own and stops it again`() {
        val process = FakeServerProcess()
        startedProcesses += process
        val starts = AtomicInteger()
        val service = service(processFactory = {
            starts.incrementAndGet()
            process
        })
        serve(process, status("OK", "falleng0d"))

        val state = checkStatus(service, "/fake/copilot-language-server")

        assertEquals(CopilotServerState.Ready("falleng0d"), state)
        assertEquals(1, starts.get())
        assertTrue(process.isTerminated)
        assertEquals(CopilotServerState.Stopped, service.state.value)
    }

    @Test
    fun `a status check reuses the server that is already running`() {
        val process = FakeServerProcess()
        startedProcesses += process
        val starts = AtomicInteger()
        val service = service(processFactory = {
            starts.incrementAndGet()
            process
        })
        serve(process, status("OK", "falleng0d"))

        service.ensureStarted()
        awaitState(service) { it is CopilotServerState.Ready }
        val state = checkStatus(service, "/fake/copilot-language-server")

        assertEquals(CopilotServerState.Ready("falleng0d"), state)
        assertEquals(1, starts.get())
        assertFalse(process.isTerminated)
    }

    @Test
    fun `a status check on a server that never answers fails`() {
        val process = FakeServerProcess()
        startedProcesses += process
        val service = service(processFactory = { process }, handshakeTimeoutMs = 100L)

        val state = checkStatus(service, "/fake/copilot-language-server")

        assertTrue((state as CopilotServerState.Failed).reason.contains("did not answer"))
        assertTrue(process.isTerminated)
    }

    private fun checkStatus(service: CopilotLanguageServerService, explicitPath: String): CopilotServerState =
        runBlocking {
            withTimeout(AWAIT_TIMEOUT_MS) { service.checkStatusNow(explicitPath) }
        }

    private fun serviceStarting(process: FakeServerProcess): CopilotLanguageServerService {
        startedProcesses += process
        return service(processFactory = { process })
    }

    private fun service(
        settings: CopilotServerSettings = settingsWith(""),
        processFactory: (String) -> CopilotServerProcess,
        locator: (String) -> CopilotServerLocation = { foundAt("/fake/copilot-language-server") },
        retryDelaysMs: List<Long> = listOf(1L),
        handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    ) = CopilotLanguageServerService(
        scope,
        settings,
        { executablePath -> processFactory(executablePath) },
        locator,
        { connection ->
            CopilotLanguageServer(
                connection = connection,
                editorInfo = CopilotEditorInfo("IntelliJ IDEA", "2026.2"),
                editorPluginInfo = CopilotEditorInfo("TUILaunch", "0.7.0"),
                openDocument = {},
            )
        },
        retryDelaysMs,
        handshakeTimeoutMs,
    )

    private fun settingsWith(path: String) = object : CopilotServerSettings {
        override val explicitServerPath: String = path
    }

    private fun foundAt(path: String) =
        CopilotServerLocation.Found(Path.of(path), CopilotServerSource.EXPLICIT_PATH)

    private fun status(status: String, user: String?) = JsonObject().apply {
        addProperty("status", status)
        if (user != null) addProperty("user", user)
    }

    private fun statusChange(kind: String, message: String?) = JsonObject().apply {
        addProperty("kind", kind)
        addProperty("busy", false)
        if (message != null) addProperty("message", message)
    }

    private fun stateAnsweredFor(serverStatus: String): CopilotServerState {
        val process = FakeServerProcess()
        val service = serviceStarting(process)
        serve(process, status(serverStatus, "falleng0d"))

        service.ensureStarted()

        return awaitState(service) { it is CopilotServerState.NotSignedIn || it is CopilotServerState.Ready }
    }

    private fun checkStatusOptionsOf(process: FakeServerProcess): JsonObject =
        process.requests
            .last { it["method"]?.asString == "checkStatus" }
            .getAsJsonObject("params")
            .getAsJsonObject("options")

    private fun serve(process: FakeServerProcess, vararg statuses: JsonObject): Job = scope.launch(Dispatchers.IO) {
        val peer = process.peer
        var statusChecks = 0
        while (true) {
            val message = try {
                peer.read()
            } catch (failure: IOException) {
                return@launch
            }
            val method = message["method"]?.asString ?: continue
            process.methods += method
            process.requests += message
            val id = message["id"]?.takeUnless { it.isJsonNull }?.asInt
            when {
                method == "initialize" && id != null -> peer.respond(id, JsonParser.parseString(INITIALIZE_RESULT))
                method == "checkStatus" && id != null ->
                    peer.respond(id, statuses[minOf(statusChecks++, statuses.lastIndex)])
                method == "shutdown" && id != null -> peer.respondNull(id)
                method == "exit" -> {
                    process.terminate()
                    return@launch
                }
                id != null -> peer.respondError(id, -32601, "Method not found: $method")
            }
        }
    }

    private fun awaitScopeCompletion() = runBlocking {
        withTimeout(SCOPE_COMPLETION_TIMEOUT_MS) { scope.coroutineContext.job.join() }
    }

    private fun awaitState(
        service: CopilotLanguageServerService,
        predicate: (CopilotServerState) -> Boolean,
    ): CopilotServerState = runBlocking {
        withTimeout(AWAIT_TIMEOUT_MS) { service.state.first(predicate) }
    }

    private class FakeServerProcess : CopilotServerProcess {
        private val streams = CopilotTestStreams()
        private val terminationListeners = CopyOnWriteArrayList<() -> Unit>()

        val peer: CopilotTestPeer get() = streams.peer
        val methods = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<JsonObject>()

        @Volatile
        override var isTerminated: Boolean = false
            private set

        override val standardOutput: InputStream get() = streams.clientInput

        override val standardInput: OutputStream get() = streams.clientOutput

        override fun onTerminated(listener: () -> Unit) {
            terminationListeners += listener
            if (isTerminated) listener()
        }

        override fun destroy() = terminate()

        fun terminate() {
            if (isTerminated) return
            isTerminated = true
            streams.peer.close()
            terminationListeners.forEach { it() }
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val SCOPE_COMPLETION_TIMEOUT_MS = 3_000L
        const val UNREACHABLE_TIMEOUT_MS = 600_000L

        const val INITIALIZE_RESULT = """
            {"capabilities":{"inlineCompletionProvider":{}},
            "serverInfo":{"name":"GitHub Copilot Language Server","version":"1.532.6"}}
        """
    }
}
