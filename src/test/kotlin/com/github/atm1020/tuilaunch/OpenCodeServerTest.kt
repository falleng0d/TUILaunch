package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.OpenCodeHealthProbe
import com.github.atm1020.tuilaunch.resume.OpenCodeServerProcess
import com.github.atm1020.tuilaunch.resume.OpenCodeServerProcessFactory
import com.github.atm1020.tuilaunch.resume.OpenCodeServerService
import com.github.atm1020.tuilaunch.resume.allocateFreePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val FIRST_TAB = "6cd3a1f0-1c5a-4b4e-9f26-6b4a1c5e7d01"
private const val SECOND_TAB = "6cd3a1f0-1c5a-4b4e-9f26-6b4a1c5e7d02"
private const val FIRST_PORT = 45001
private const val WAIT_MILLIS = 10_000L

class OpenCodeServerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processes = FakeServerProcesses()
    private val health = FakeHealthProbe()
    private val killedPids = Collections.synchronizedList(mutableListOf<Long>())
    private val nextPort = AtomicInteger(FIRST_PORT)

    @After
    fun stopTheScope() {
        scope.cancel()
    }

    @Test
    fun `the first tab starts one server and gets its address`() {
        val server = newServer()

        val address = runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }

        assertEquals("http://127.0.0.1:$FIRST_PORT", address)
        assertEquals(
            listOf("opencode", "serve", "--port", "$FIRST_PORT", "--hostname", "127.0.0.1"),
            processes.started.single().tokens,
        )
        assertEquals(workingDirectory().toString(), processes.started.single().workingDirectory)
        assertEquals("http://127.0.0.1:$FIRST_PORT", health.asked)
    }

    @Test
    fun `a second tab joins the server the first one started`() {
        val server = newServer()

        val first = runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }
        val second = runBlocking { server.acquire(SECOND_TAB, AgentCommand.parse("opencode")) }

        assertEquals(first, second)
        assertEquals(1, processes.started.size)
    }

    @Test
    fun `the server keeps running until the last tab is released`() {
        val server = newServer()
        runBlocking {
            server.acquire(FIRST_TAB, AgentCommand.parse("opencode"))
            server.acquire(SECOND_TAB, AgentCommand.parse("opencode"))
        }

        server.release(FIRST_TAB)
        Thread.sleep(50)
        assertFalse(processes.started.single().isTerminated)

        server.release(SECOND_TAB)

        awaitUntil("The last release never stopped the server") { processes.started.single().isTerminated }
        assertEquals(1, processes.started.single().destroyCount)
        assertEquals(0, processes.started.single().killCount)
    }

    @Test
    fun `releasing the same tab twice stops the server once`() {
        val server = newServer()
        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }

        server.release(FIRST_TAB)
        server.release(FIRST_TAB)
        server.release("a tab that never attached")

        awaitUntil("The release never stopped the server") { processes.started.single().isTerminated }
        assertEquals(1, processes.started.single().destroyCount)
    }

    @Test
    fun `a server that will not die is killed`() {
        val server = newServer()
        processes.theProcessIgnoresDestroy = true
        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }

        server.release(FIRST_TAB)

        awaitUntil("The stubborn server was never killed") { processes.started.single().killCount == 1 }
    }

    @Test
    fun `a server that dies while a tab is attached comes back on the same port`() {
        val server = newServer()
        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }

        processes.started.single().crash()

        awaitUntil("The server was never started again") { processes.started.size == 2 }
        assertEquals(
            listOf("opencode", "serve", "--port", "$FIRST_PORT", "--hostname", "127.0.0.1"),
            processes.started.last().tokens,
        )
        assertEquals(
            "http://127.0.0.1:$FIRST_PORT",
            runBlocking { server.acquire(SECOND_TAB, AgentCommand.parse("opencode")) },
        )
    }

    @Test
    fun `a server that dies with no tab attached is not started again`() {
        val server = newServer()
        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }
        server.release(FIRST_TAB)
        awaitUntil("The release never stopped the server") { processes.started.single().isTerminated }

        Thread.sleep(50)

        assertEquals(1, processes.started.size)
    }

    @Test
    fun `a server that keeps dying is given up on`() {
        val server = newServer(restartDelaysMs = listOf(0L, 0L))
        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }

        repeat(3) { attempt ->
            awaitUntil("The server was not started ${attempt + 1} times") { processes.started.size == attempt + 1 }
            processes.started.last().crash()
        }

        Thread.sleep(100)
        assertEquals(3, processes.started.size)
    }

    @Test
    fun `a server that never answers is stopped and reported as unusable`() {
        val server = newServer(startupTimeoutMs = 100L)
        health.healthy = false

        val address = runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }

        assertNull(address)
        assertTrue(processes.started.single().isTerminated)
        assertFalse(Files.exists(stateFile()))
    }

    @Test
    fun `the running server is written down and forgotten again`() {
        val server = newServer()
        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }

        awaitUntil("The running server was never written down") { Files.exists(stateFile()) }
        val record = Files.readString(stateFile())
        assertTrue(record, record.contains("\"port\":$FIRST_PORT"))
        assertTrue(record, record.contains("\"pid\":${processes.started.single().pid}"))

        server.release(FIRST_TAB)

        awaitUntil("The stopped server was never forgotten") { !Files.exists(stateFile()) }
    }

    @Test
    fun `a server left by an earlier run is ended before a new one starts`() {
        Files.createDirectories(stateFile().parent)
        Files.writeString(stateFile(), """{"pid":4242,"port":1234}""")
        val server = newServer()

        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("opencode")) }
        processes.started.single().crash()
        awaitUntil("The server was never started again") { processes.started.size == 2 }

        assertEquals(listOf(4242L), killedPids.toList())
    }

    @Test
    fun `an environment prefix in the command reaches the server process`() {
        val server = newServer()

        runBlocking { server.acquire(FIRST_TAB, AgentCommand.parse("OPENCODE_CONFIG=/tmp/one.json opencode")) }

        val started = processes.started.single()
        assertEquals(mapOf("OPENCODE_CONFIG" to "/tmp/one.json"), started.environment)
        assertEquals("opencode", started.tokens.first())
    }

    @Test
    fun `a free port is a port nothing else listens on`() {
        val port = allocateFreePort()

        assertTrue("$port", port in 1..65535)
    }

    @Test
    fun `the health path is the one the opencode server publishes`() {
        assertEquals("/global/health", OpenCodeServerService.HEALTH_PATH)
    }

    private fun newServer(
        restartDelaysMs: List<Long> = listOf(0L),
        startupTimeoutMs: Long = WAIT_MILLIS,
    ): OpenCodeServerService = OpenCodeServerService(
        scope = scope,
        processes = processes,
        health = health,
        freePort = { nextPort.getAndIncrement() },
        endTheServerWithPid = { pid -> killedPids.add(pid) },
        stateFile = stateFile(),
        workingDirectory = workingDirectory().toString(),
        restartDelaysMs = restartDelaysMs,
        pollIntervalMs = 5L,
        startupTimeoutMs = startupTimeoutMs,
        stopGraceMs = 100L,
    )

    private fun stateFile(): Path =
        temporaryFolder.root.toPath().resolve("state").resolve("opencode").resolve("server.json")

    private fun workingDirectory(): Path = temporaryFolder.root.toPath()

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail(message)
    }
}

private class FakeHealthProbe : OpenCodeHealthProbe {
    @Volatile
    var healthy = true
    @Volatile
    var asked: String? = null

    override suspend fun answers(address: String): Boolean {
        asked = address
        return healthy
    }
}

private class FakeServerProcesses : OpenCodeServerProcessFactory {
    private val processes = Collections.synchronizedList(mutableListOf<FakeServerProcess>())
    private val pids = AtomicLong(9000L)

    @Volatile
    var theProcessIgnoresDestroy = false

    val started: List<FakeServerProcess> get() = synchronized(processes) { processes.toList() }

    override fun start(
        tokens: List<String>,
        environment: Map<String, String>,
        workingDirectory: String?,
    ): OpenCodeServerProcess = FakeServerProcess(
        tokens = tokens,
        environment = environment,
        workingDirectory = workingDirectory,
        pid = pids.getAndIncrement(),
        ignoresDestroy = theProcessIgnoresDestroy,
    ).also { processes.add(it) }
}

private class FakeServerProcess(
    val tokens: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String?,
    override val pid: Long,
    private val ignoresDestroy: Boolean,
) : OpenCodeServerProcess {

    @Volatile
    override var isTerminated = false
        private set

    @Volatile
    var destroyCount = 0
        private set

    @Volatile
    var killCount = 0
        private set

    private val listeners = Collections.synchronizedList(mutableListOf<() -> Unit>())

    override fun onTerminated(listener: () -> Unit) {
        listeners.add(listener)
        if (isTerminated) listener()
    }

    override fun destroy() {
        destroyCount++
        if (!ignoresDestroy) end()
    }

    override fun kill() {
        killCount++
        end()
    }

    fun crash() = end()

    private fun end() {
        if (isTerminated) return
        isTerminated = true
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }
}
