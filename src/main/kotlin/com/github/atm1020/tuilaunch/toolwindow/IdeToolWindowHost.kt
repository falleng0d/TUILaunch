package com.github.atm1020.tuilaunch.toolwindow

import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabFile
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

data class ToolWindowSize(val width: Int, val height: Int)

enum class ToolWindowSizeAxis { WIDTH, HEIGHT, BOTH }

private class ContentTabHandle(val content: Content) {
    override fun equals(other: Any?): Boolean = this === other || (other is ContentTabHandle && other.content === content)
    override fun hashCode(): Int = System.identityHashCode(content)
    override fun toString(): String = "ContentTabHandle(${content.displayName})"
}

private const val MIN_TOOL_WINDOW_WIDTH = 400
private const val MIN_TOOL_WINDOW_HEIGHT = 250
private const val SCREEN_MARGIN = 80
private const val FOCUS_OWNER_PROPERTY = "focusOwner"

const val APPEND_TAB = -1

private class TuiSessionMarker(val lifetime: CheckedDisposable, val record: () -> TuiSessionRecord?)

private val TUI_SESSION = Key.create<TuiSessionMarker>("TUILaunch.session")

internal fun Content.belongsToALiveTuiSession(): Boolean = getUserData(TUI_SESSION)?.lifetime?.isDisposed == false

internal fun Content.tuiSessionRecord(): TuiSessionRecord? =
    getUserData(TUI_SESSION)?.takeIf { !it.lifetime.isDisposed }?.record?.invoke()

open class IdeToolWindowHost(private val toolWindow: ToolWindow?) {

    open fun isVisible(): Boolean = requireToolWindow().isVisible

    open fun isPinned(): Boolean = !requireToolWindow().isAutoHide

    open fun show() = requireToolWindow().show(null)
    open fun hide() = requireToolWindow().hide(null)

    open fun addTab(
        component: JComponent,
        title: String,
        disposable: CheckedDisposable,
        record: () -> TuiSessionRecord?,
        index: Int = APPEND_TAB,
    ): Any {
        val toolWindow = requireToolWindow()
        val content = newContent(component, title)
        attach(content, disposable, record)
        toolWindow.contentManager.addContent(content, index)
        return ContentTabHandle(content)
    }

    open fun selectTab(handle: Any) {
        requireToolWindow().contentManager.setSelectedContent(contentOf(handle), true)
    }

    open fun activeTab(): Any? =
        requireToolWindow().contentManager.selectedContent?.let { ContentTabHandle(it) }

    open fun orderedHandles(): List<Any> =
        requireToolWindow().contentManager.contents.map { ContentTabHandle(it) }

    open fun removeTab(handle: Any) {
        requireToolWindow().contentManager.removeContent(contentOf(handle), true)
    }

    open fun createEditorTab(component: JComponent, title: String): Any = ContentTabHandle(newContent(component, title))

    open fun attachSession(handle: Any, disposable: CheckedDisposable, record: () -> TuiSessionRecord?) {
        attach(contentOf(handle), disposable, record)
    }

    private fun newContent(component: JComponent, title: String): Content {
        val content = ContentFactory.getInstance().createContent(component, title, false)
        content.isCloseable = true
        return content
    }

    private fun attach(content: Content, disposable: CheckedDisposable, record: () -> TuiSessionRecord?) {
        content.putUserData(TUI_SESSION, TuiSessionMarker(disposable, record))
        Disposer.register(content, disposable)
    }

    open fun selectEditorTab(handle: Any, requestFocus: Boolean) {
        val project = requireToolWindow().project
        val file = editorTabFileOf(contentOf(handle)) ?: return
        FileEditorManager.getInstance(project).openFile(file, requestFocus)
    }

    open fun releaseEditorTab(handle: Any) {
        Disposer.dispose(contentOf(handle))
    }

    open fun selectNeighbourEditorTab(handle: Any, forward: Boolean) {
        val actionManager = ActionManager.getInstance()
        val actionId = if (forward) IdeActions.ACTION_NEXT_TAB else IdeActions.ACTION_PREVIOUS_TAB
        val action = actionManager.getAction(actionId) ?: return
        actionManager.tryToExecute(action, null, contentOf(handle).component, ActionPlaces.KEYBOARD_SHORTCUT, true)
    }

    open fun focusOwner(): Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

    open fun onFocusMoved(listener: (Component) -> Unit) {
        val toolWindow = requireToolWindow()
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusOwnerListener = PropertyChangeListener { event -> (event.newValue as? Component)?.let(listener) }
        focusManager.addPropertyChangeListener(FOCUS_OWNER_PROPERTY, focusOwnerListener)
        Disposer.register(toolWindow.disposable) {
            focusManager.removePropertyChangeListener(FOCUS_OWNER_PROPERTY, focusOwnerListener)
        }
    }

    private fun editorTabFileOf(content: Content): ToolWindowEditorTabFile? {
        val toolWindow = requireToolWindow()
        val tabManager = ToolWindowEditorTabManager.getInstance(toolWindow.project)
        return FileEditorManager.getInstance(toolWindow.project).openFiles
            .filterIsInstance<ToolWindowEditorTabFile>()
            .firstOrNull { it.toolWindowId == toolWindow.id && tabManager.getSession(it)?.content === content }
    }

    open fun currentSize(): ToolWindowSize? {
        val component = requireToolWindow().component
        val size = if (canResizeWindowDirectly()) {
            SwingUtilities.getWindowAncestor(component)?.size ?: component.size
        } else {
            component.size
        }
        if (size.width <= 0 || size.height <= 0) return null
        return ToolWindowSize(size.width, size.height)
    }

    open fun isDockedHorizontally(): Boolean = requireToolWindow().anchor.isHorizontal

    open fun onAnchorChanged(listener: (Boolean) -> Unit) {
        val toolWindow = requireToolWindow()
        var dockedHorizontally = toolWindow.anchor.isHorizontal
        toolWindow.project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    val nowDockedHorizontally = toolWindow.anchor.isHorizontal
                    if (nowDockedHorizontally == dockedHorizontally) return
                    dockedHorizontally = nowDockedHorizontally
                    listener(nowDockedHorizontally)
                }
            },
        )
    }

    open fun sizeAxis(): ToolWindowSizeAxis {
        if (canResizeWindowDirectly()) return ToolWindowSizeAxis.BOTH
        return if (requireToolWindow().anchor.isHorizontal) ToolWindowSizeAxis.HEIGHT else ToolWindowSizeAxis.WIDTH
    }

    open fun applySize(size: ToolWindowSize) {
        val clamped = clampSize(size) ?: return
        val toolWindow = requireToolWindow()
        val component = toolWindow.component
        val dimension = Dimension(clamped.width, clamped.height)
        val window = SwingUtilities.getWindowAncestor(component)
        if (window != null && canResizeWindowDirectly()) {
            window.size = dimension
            window.validate()
        } else if (toolWindow is ToolWindowEx && toolWindow.type == ToolWindowType.DOCKED) {
            stretchDockedToolWindow(toolWindow, clamped)
        } else {
            component.preferredSize = dimension
            component.revalidate()
        }
    }

    open fun onSizeChanged(listener: () -> Unit) {
        requireToolWindow().component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                listener()
            }
        })
    }

    open fun onTabSelected(listener: (Any) -> Unit) {
        requireToolWindow().contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) {
                    listener(ContentTabHandle(event.content))
                }
            }
        })
    }

    open fun onTabRemoved(beforeRemoval: (Any) -> Unit, afterRemoval: (Any) -> Unit) {
        installContentManagerListener(object : ContentManagerListener {
            override fun contentRemoveQuery(event: ContentManagerEvent) {
                beforeRemoval(ContentTabHandle(event.content))
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                afterRemoval(ContentTabHandle(event.content))
            }
        })
    }

    open fun onTabAdded(listener: (Any) -> Unit) {
        val toolWindow = requireToolWindow()
        val contentManager = toolWindow.contentManager
        val addListener = object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                listener(ContentTabHandle(event.content))
            }
        }
        contentManager.addRecursiveContentManagerListener(addListener)
        Disposer.register(toolWindow.disposable) {
            contentManager.removeRecursiveContentManagerListener(addListener)
        }
    }

    open fun isTabRemovedForDrag(handle: Any): Boolean =
        contentOf(handle).getUserData(Content.TEMPORARY_REMOVED_KEY) == true

    open fun isTabAttachedToToolWindow(handle: Any): Boolean = contentOf(handle).manager != null

    private fun installContentManagerListener(listener: ContentManagerListener) {
        val toolWindow = requireToolWindow()
        val contentManager = toolWindow.contentManager
        contentManager.addContentManagerListener(listener)
        Disposer.register(toolWindow.disposable) {
            contentManager.removeContentManagerListener(listener)
        }
    }

    open fun handleFor(content: Content): Any = ContentTabHandle(content)

    open fun setTabTitle(handle: Any, title: String) {
        contentOf(handle).setDisplayName(title)
    }

    fun contentOf(handle: Any): Content = (handle as ContentTabHandle).content

    private fun canResizeWindowDirectly(): Boolean = when (requireToolWindow().type) {
        ToolWindowType.FLOATING, ToolWindowType.WINDOWED -> true
        else -> false
    }

    private fun stretchDockedToolWindow(toolWindow: ToolWindowEx, size: ToolWindowSize) {
        val current = currentSize() ?: return
        when (toolWindow.anchor) {
            ToolWindowAnchor.TOP,
            ToolWindowAnchor.BOTTOM -> toolWindow.stretchHeight(size.height - current.height)
            ToolWindowAnchor.LEFT,
            ToolWindowAnchor.RIGHT -> toolWindow.stretchWidth(size.width - current.width)
        }
    }

    private fun clampSize(size: ToolWindowSize): ToolWindowSize? {
        if (size.width <= 0 || size.height <= 0) return null
        val bounds = currentScreenBounds() ?: return null
        val maxWidth = max(MIN_TOOL_WINDOW_WIDTH, bounds.width - SCREEN_MARGIN)
        val maxHeight = max(MIN_TOOL_WINDOW_HEIGHT, bounds.height - SCREEN_MARGIN)
        return ToolWindowSize(
            width = min(max(size.width, MIN_TOOL_WINDOW_WIDTH), maxWidth),
            height = min(max(size.height, MIN_TOOL_WINDOW_HEIGHT), maxHeight),
        )
    }

    private fun currentScreenBounds(): Rectangle? {
        if (GraphicsEnvironment.isHeadless()) return null
        val window = SwingUtilities.getWindowAncestor(requireToolWindow().component)
        val configuration = window?.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
        return configuration.bounds
    }

    private fun requireToolWindow(): ToolWindow = requireNotNull(toolWindow) { "ToolWindow is required" }
}
