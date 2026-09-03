package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.terminal.TerminalSession
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSize
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSizeAxis
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import javax.swing.JComponent
import javax.swing.JPanel

private const val MAXIMUM_LEFTOVER_SESSIONS = 100

internal fun closeSessionsLeftOpenByEarlierTests(service: TuiAppLaunchService) {
    repeat(MAXIMUM_LEFTOVER_SESSIONS) {
        if (!aSessionIsStillOpen(service)) return
        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
    error("A session left open by an earlier test could not be closed")
}

private fun aSessionIsStillOpen(service: TuiAppLaunchService): Boolean {
    val probeHost = FakeHost()
    service.host = probeHost
    service.focusTui()
    return probeHost.showCount > 0
}

internal data class SentKey(val keyCode: Int, val modifiers: Int, val keyChar: Char)

internal class FakeSession(private val terminalAcceptsText: Boolean = true) {
    val component: JComponent = JPanel()
    var focusCount = 0
    val sentText = mutableListOf<String>()
    val sentKeys = mutableListOf<SentKey>()
    fun requestFocus() {
        focusCount++
    }

    fun asTerminalSession(): TerminalSession = TerminalSession(
        component = component,
        requestFocus = { requestFocus() },
        registerTerminationCallback = {},
        sendKey = { keyCode, modifiers, keyChar -> sentKeys.add(SentKey(keyCode, modifiers, keyChar)) },
        sendText = { text ->
            sentText.add(text)
            terminalAcceptsText
        },
    )
}

internal class FakeFactory(private val sessions: List<FakeSession>) : TerminalSessionFactory {
    private var index = 0

    constructor(session: FakeSession) : this(listOf(session))

    override fun create(parent: Disposable, command: String): TerminalSession = sessions[index++].asTerminalSession()
}

internal class DeferredFactory(private val sessions: List<FakeSession>) : TerminalSessionFactory {
    private var onCreated: ((TerminalSession) -> Unit)? = null
    private var onFailed: ((Throwable) -> Unit)? = null
    private var index = 0
    var createCount = 0

    constructor(session: FakeSession) : this(listOf(session))

    override fun create(parent: Disposable, command: String): TerminalSession = error("Use async creation")

    override fun createAsync(
        parent: Disposable,
        command: String,
        onCreated: (TerminalSession) -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        createCount++
        this.onCreated = onCreated
        this.onFailed = onFailed
    }

    fun finish() {
        val callback = takePendingCreation() ?: return
        callback(sessions[index++].asTerminalSession())
    }

    fun fail() {
        val callback = onFailed ?: return
        takePendingCreation()
        callback(IllegalStateException("launch failed"))
    }

    private fun takePendingCreation(): ((TerminalSession) -> Unit)? {
        val callback = onCreated
        onCreated = null
        onFailed = null
        return callback
    }
}

internal class FakeHost : IdeToolWindowHost(null) {
    var visible = false
    var pinned = true
    var showCount = 0
    private var selected: Any? = null
    val tabs = mutableListOf<Any>()
    val titles = mutableListOf<String>()
    val disposables = mutableListOf<CheckedDisposable>()
    var size: ToolWindowSize? = null
    var axis = ToolWindowSizeAxis.HEIGHT
    val appliedSizes = mutableListOf<ToolWindowSize>()
    var emitStaleResizeOnApply = false
    private var sizeChanged: (() -> Unit)? = null
    private var tabSelected: ((Any) -> Unit)? = null
    private var tabAdded: ((Any) -> Unit)? = null
    private var tabRemoving: ((Any) -> Unit)? = null
    private var tabRemoved: ((Any) -> Unit)? = null
    private val tabsRemovedForDrag = mutableSetOf<Any>()
    private val disposableByTab = mutableMapOf<Any, CheckedDisposable>()

    override fun isVisible(): Boolean = visible
    override fun isPinned(): Boolean = pinned
    override fun show() {
        visible = true
        showCount++
    }

    override fun hide() {
        visible = false
    }

    override fun addTab(component: JComponent, title: String, disposable: Disposable): Any {
        val handle = Any()
        tabs.add(handle)
        titles.add(title)
        (disposable as? CheckedDisposable)?.let {
            disposables.add(it)
            disposableByTab[handle] = it
        }
        tabAdded?.invoke(handle)
        if (selected == null) {
            selected = handle
            tabSelected?.invoke(handle)
        }
        return handle
    }

    override fun selectTab(handle: Any) {
        if (selected === handle) return
        selected = handle
        tabSelected?.invoke(handle)
    }

    override fun activeTab(): Any? = selected

    override fun orderedHandles(): List<Any> = tabs.toList()

    override fun removeTab(handle: Any) {
        val index = tabs.indexOf(handle)
        if (index < 0) return
        tabRemoving?.invoke(handle)
        val removingSelectedTab = selected === handle
        if (removingSelectedTab) selected = null
        tabs.removeAt(index)
        tabRemoved?.invoke(handle)
        if (removingSelectedTab) {
            selected = tabs.getOrNull(index) ?: tabs.getOrNull(index - 1)
            selected?.let { tabSelected?.invoke(it) }
        }
    }

    fun dragTab(from: Int, to: Int) {
        val handle = detachTabForDrag(from)
        tabs.add(to, handle)
        tabAdded?.invoke(handle)
        selectTab(handle)
        tabsRemovedForDrag.remove(handle)
    }

    fun dragTabOutOfTheStrip(from: Int) {
        tabsRemovedForDrag.remove(detachTabForDrag(from))
    }

    fun dragTabIntoTheEditor(from: Int) {
        val handle = detachTabForDrag(from)
        disposableByTab[handle]?.let { Disposer.dispose(it) }
        tabsRemovedForDrag.remove(handle)
    }

    private fun detachTabForDrag(from: Int): Any {
        val handle = tabs[from]
        tabsRemovedForDrag.add(handle)
        if (selected === handle) selected = tabs.getOrNull(from + 1) ?: tabs.getOrNull(from - 1)
        tabs.removeAt(from)
        tabRemoved?.invoke(handle)
        selected?.let { tabSelected?.invoke(it) }
        return handle
    }

    fun visiblePositionOfActiveTab(): Int = tabs.indexOf(selected) + 1

    override fun isTabRemovedForDrag(handle: Any): Boolean = handle in tabsRemovedForDrag

    override fun isTabAttachedToToolWindow(handle: Any): Boolean = handle in tabs

    override fun currentSize(): ToolWindowSize? = size

    override fun sizeAxis(): ToolWindowSizeAxis = axis

    override fun applySize(size: ToolWindowSize) {
        appliedSizes.add(size)
        if (emitStaleResizeOnApply) {
            sizeChanged?.invoke()
        }
        this.size = size
    }

    override fun onSizeChanged(listener: () -> Unit) {
        sizeChanged = listener
    }

    override fun onTabSelected(listener: (Any) -> Unit) {
        tabSelected = listener
    }

    override fun onTabAdded(listener: (Any) -> Unit) {
        tabAdded = listener
    }

    override fun onTabRemoved(beforeRemoval: (Any) -> Unit, afterRemoval: (Any) -> Unit) {
        tabRemoving = beforeRemoval
        tabRemoved = afterRemoval
    }

    fun triggerSizeChanged() {
        sizeChanged?.invoke()
    }

    fun triggerTabSelected(handle: Any) {
        tabSelected?.invoke(handle)
    }

    fun triggerTabRemoved(handle: Any) {
        tabRemoved?.invoke(handle)
    }
}
