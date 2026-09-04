package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.COPILOT_INLINE_COMPLETION_PROVIDER_ID
import com.github.atm1020.tuilaunch.copilot.CopilotInlineCompletionProvider
import com.github.atm1020.tuilaunch.copilot.CopilotServerState
import com.github.atm1020.tuilaunch.copilot.NOT_SIGNED_IN_PREFIX
import com.github.atm1020.tuilaunch.copilot.notSignedInReason
import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import org.jdom.Element
import kotlin.time.Duration.Companion.milliseconds

private const val LANG_MODULE = "com.intellij.modules.lang"

class CopilotInlineCompletionProviderTest : BasePlatformTestCase() {

    private var boxDisposable: Disposable? = null
    private lateinit var settings: FakePromptBoxCompletionSettings
    private lateinit var backend: FakeCopilotCompletionBackend
    private val announcedReasons = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        settings = FakePromptBoxCompletionSettings()
        backend = FakeCopilotCompletionBackend()
        announcedReasons.clear()
    }

    override fun tearDown() {
        try {
            boxDisposable?.let { Disposer.dispose(it) }
            boxDisposable = null
        } finally {
            super.tearDown()
        }
    }

    private fun newProvider() = CopilotInlineCompletionProvider(
        settings = settings,
        backend = { backend },
        announce = { reason -> announcedReasons.add(reason) },
    )

    private fun newInstalledBox(): PromptBox {
        val disposable = Disposer.newDisposable("CopilotInlineCompletionProviderTest")
        boxDisposable = disposable
        return PromptBox(project, disposable, promptDocument = { null }).also { it.installEditor() }
    }

    private fun manualCallOn(editor: Editor): InlineCompletionEvent =
        InlineCompletionEvent.ManualCall(editor, newProvider().id, UserDataHolderBase())

    private fun declaredProviderExtension(): Element =
        JDOMUtil.load(CopilotInlineCompletionProvider::class.java, "/META-INF/plugin.xml")
            .getChild("extensions")
            .getChildren("inline.completion.provider")
            .single()

    private fun declaredDependencies(): List<String> =
        JDOMUtil.load(CopilotInlineCompletionProvider::class.java, "/META-INF/plugin.xml")
            .getChildren("depends")
            .map { it.text }

    fun testAnEditorThatIsNotAPromptBoxIsNeverServed() {
        myFixture.configureByText("notes.md", "Fix the <caret>")

        assertFalse(newProvider().isEnabled(manualCallOn(myFixture.editor)))
    }

    fun testAPromptBoxIsServedWhileTheSourceIsCopilotAndTheServerIsReady() {
        val box = newInstalledBox()

        assertTrue(newProvider().isEnabled(manualCallOn(box.editor)))
    }

    fun testAPromptBoxIsNotServedWhileTheSourceIsJetBrainsAi() {
        val box = newInstalledBox()
        settings.completionSource = PromptBoxCompletionSource.JETBRAINS_AI

        assertFalse(newProvider().isEnabled(manualCallOn(box.editor)))
        assertEquals(0, backend.startRequests)
    }

    fun testAnEventThatIsNeitherTypingNorAManualCallIsNotServed() {
        val box = newInstalledBox()

        assertFalse(newProvider().isEnabled(OtherKindOfEvent(manualCallOn(box.editor))))
    }

    fun testAStoppedServerIsAskedToStartOnceAndTheEventFallsThrough() {
        val box = newInstalledBox()
        backend.serverState = CopilotServerState.Stopped

        assertFalse(newProvider().isEnabled(manualCallOn(box.editor)))
        assertEquals(1, backend.startRequests)
    }

    fun testAStartingServerLetsTheEventFallThroughWithoutStartingItAgain() {
        val box = newInstalledBox()
        backend.serverState = CopilotServerState.Starting

        assertFalse(newProvider().isEnabled(manualCallOn(box.editor)))
        assertEquals(0, backend.startRequests)
    }

    fun testAServerWithoutABinaryIsAnnouncedOncePerReason() {
        val box = newInstalledBox()
        backend.serverState = CopilotServerState.NotConfigured("No copilot-language-server binary was found")
        val provider = newProvider()

        assertFalse(provider.isEnabled(manualCallOn(box.editor)))
        assertFalse(provider.isEnabled(manualCallOn(box.editor)))

        assertEquals(listOf("No copilot-language-server binary was found"), announcedReasons)
    }

    fun testANotSignedInServerAndAFailedServerAreAnnouncedSeparately() {
        val box = newInstalledBox()
        val provider = newProvider()

        backend.serverState = CopilotServerState.NotSignedIn("NotSignedIn")
        assertFalse(provider.isEnabled(manualCallOn(box.editor)))
        backend.serverState = CopilotServerState.Failed("The process exited")
        assertFalse(provider.isEnabled(manualCallOn(box.editor)))

        assertEquals(listOf(notSignedInReason("NotSignedIn"), "The process exited"), announcedReasons)
    }

    fun testTheAnnouncementRepeatsTheStatusTheServerAnswered() {
        val box = newInstalledBox()
        val provider = newProvider()

        backend.serverState = CopilotServerState.NotSignedIn("MaybeOK")
        assertFalse(provider.isEnabled(manualCallOn(box.editor)))
        backend.serverState = CopilotServerState.NotSignedIn("You are not signed into GitHub.")
        assertFalse(provider.isEnabled(manualCallOn(box.editor)))

        assertEquals(
            listOf(
                "$NOT_SIGNED_IN_PREFIX MaybeOK",
                "$NOT_SIGNED_IN_PREFIX You are not signed into GitHub",
            ),
            announcedReasons,
        )
    }

    fun testOnlyAManualCallRestartsTheRunningRequest() {
        val box = newInstalledBox()
        val provider = newProvider()
        val manualCall = manualCallOn(box.editor)

        assertTrue(provider.restartOn(manualCall))
        assertFalse(provider.restartOn(OtherKindOfEvent(manualCall)))
    }

    fun testTypingWaitsOutASmallPauseBeforeAskingTheServer() {
        val box = newInstalledBox()
        val request = requireNotNull(manualCallOn(box.editor).toRequest())

        val delay = runBlocking { newProvider().getDebounceDelay(request) }

        assertEquals(120.milliseconds, delay)
    }

    fun testTheProviderIdIsTheOneTheDescriptorRegisters() {
        assertEquals(COPILOT_INLINE_COMPLETION_PROVIDER_ID, newProvider().id.id)
        assertEquals(COPILOT_INLINE_COMPLETION_PROVIDER_ID, declaredProviderExtension().getAttributeValue("id"))
    }

    fun testTheDescriptorRegistersTheProviderAheadOfEveryOtherOne() {
        val declared = declaredProviderExtension()

        assertEquals(
            CopilotInlineCompletionProvider::class.java.name,
            declared.getAttributeValue("implementation"),
        )
        assertEquals("first", declared.getAttributeValue("order"))
    }

    fun testTheDescriptorDependsOnTheModuleThatOwnsTheExtensionPoint() {
        assertTrue(LANG_MODULE in declaredDependencies())
    }
}

private class OtherKindOfEvent(private val delegate: InlineCompletionEvent) : InlineCompletionEvent {

    override fun toRequest(): InlineCompletionRequest? = delegate.toRequest()
}
