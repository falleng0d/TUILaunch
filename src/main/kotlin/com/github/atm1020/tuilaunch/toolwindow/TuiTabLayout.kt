package com.github.atm1020.tuilaunch.toolwindow

import com.intellij.openapi.ui.Splitter
import com.intellij.ui.OnePixelSplitter
import javax.swing.JComponent
import kotlin.math.roundToInt

internal const val MINIMUM_PROMPT_BOX_PERCENT = 10
internal const val MAXIMUM_PROMPT_BOX_PERCENT = 90

internal fun clampPromptBoxPercent(percent: Int): Int =
    percent.coerceIn(MINIMUM_PROMPT_BOX_PERCENT, MAXIMUM_PROMPT_BOX_PERCENT)

internal class TuiTabLayout(
    terminal: JComponent,
    private val promptBox: JComponent,
    dockedHorizontally: Boolean,
    promptBoxPercent: Int,
    promptBoxVisible: Boolean,
) {
    private val splitter = OnePixelSplitter(!dockedHorizontally, terminalProportionFor(promptBoxPercent))
    private var percent = clampPromptBoxPercent(promptBoxPercent)
    private var applyingPercent = false

    var onPromptBoxPercentChanged: (Int) -> Unit = {}

    val component: JComponent get() = splitter

    var promptBoxVisible: Boolean
        get() = promptBox.isVisible
        set(value) {
            promptBox.isVisible = value
        }

    var promptBoxPercent: Int
        get() = percent
        set(value) {
            val clamped = clampPromptBoxPercent(value)
            percent = clamped
            applyingPercent = true
            try {
                splitter.proportion = terminalProportionFor(clamped)
            } finally {
                applyingPercent = false
            }
        }

    init {
        splitter.setHonorComponentsMinimumSize(false)
        splitter.firstComponent = terminal
        splitter.secondComponent = promptBox
        promptBox.isVisible = promptBoxVisible
        splitter.addPropertyChangeListener(Splitter.PROP_PROPORTION) { event ->
            val proportion = event.newValue as? Float
            if (applyingPercent || proportion == null) return@addPropertyChangeListener
            percent = promptBoxPercentFor(proportion)
            onPromptBoxPercentChanged(percent)
        }
    }

    fun setDockedHorizontally(dockedHorizontally: Boolean) {
        splitter.orientation = !dockedHorizontally
    }

    private fun terminalProportionFor(promptBoxPercent: Int): Float =
        1f - clampPromptBoxPercent(promptBoxPercent) / 100f

    private fun promptBoxPercentFor(terminalProportion: Float): Int =
        clampPromptBoxPercent(((1f - terminalProportion) * 100).roundToInt())
}
