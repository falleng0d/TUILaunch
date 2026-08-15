package com.github.atm1020.tuilaunch.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

data class ToolWindowSize(val width: Int, val height: Int)

private class ContentTabHandle(val content: Content) {
    override fun equals(other: Any?): Boolean = this === other || (other is ContentTabHandle && other.content === content)
    override fun hashCode(): Int = System.identityHashCode(content)
    override fun toString(): String = "ContentTabHandle(${content.displayName})"
}

private const val MIN_TOOL_WINDOW_WIDTH = 400
private const val MIN_TOOL_WINDOW_HEIGHT = 250
private const val SCREEN_MARGIN = 80

open class IdeToolWindowHost(private val toolWindow: ToolWindow?) {

    open fun isVisible(): Boolean = requireToolWindow().isVisible

    open fun isPinned(): Boolean = !requireToolWindow().isAutoHide

    open fun show() = requireToolWindow().show(null)
    open fun hide() = requireToolWindow().hide(null)

    open fun addTab(component: JComponent, title: String, disposable: Disposable): Any {
        val toolWindow = requireToolWindow()
        val content = ContentFactory.getInstance().createContent(component, title, false)
        content.isCloseable = true
        Disposer.register(content, disposable)
        toolWindow.contentManager.addContent(content)
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
        val toolWindow = requireToolWindow()
        val contentManager = toolWindow.contentManager
        val contentManagerListener = object : ContentManagerListener {
            override fun contentRemoveQuery(event: ContentManagerEvent) {
                beforeRemoval(ContentTabHandle(event.content))
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                afterRemoval(ContentTabHandle(event.content))
            }
        }
        contentManager.addContentManagerListener(contentManagerListener)
        Disposer.register(toolWindow.disposable) {
            contentManager.removeContentManagerListener(contentManagerListener)
        }
    }

    open fun handleFor(content: Content): Any = ContentTabHandle(content)

    open fun setTabTitle(handle: Any, title: String) {
        contentOf(handle).setDisplayName(title)
    }

    private fun contentOf(handle: Any): Content = (handle as ContentTabHandle).content

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
