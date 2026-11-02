package com.alo.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * How a press feels on iOS (AloPressBounceButtonStyle / AloChatPressStyle), instead of Material's
 * ripple: on touch-down the control shrinks and dims at once, and on release it settles back in 160ms
 * on the ease-out curve (0.23, 1, 0.32, 1). Small controls shrink a little; a wide row or card only
 * dims, as iOS's rows do.
 */
internal object AloPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === AloPressIndication
    override fun hashCode(): Int = -1

    private class PressNode(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private val progress = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                var presses = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> {
                            presses++
                            progress.snapTo(1f)
                            invalidateDraw()
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            presses = (presses - 1).coerceAtLeast(0)
                            if (presses == 0) {
                                launch {
                                    progress.animateTo(0f, tween(160, easing = AloTelegramEaseOutQuint)) { invalidateDraw() }
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val p = progress.value
            if (p <= 0.001f) {
                drawContent()
                return
            }
            val small = minOf(size.width, size.height) <= 72.dp.toPx()
            val scaleTo = if (small) 1f - 0.08f * p else 1f
            val alpha = if (small) 1f - 0.08f * p else 1f - 0.24f * p
            val paint = Paint().apply { this.alpha = alpha }
            scale(scaleTo, scaleTo) {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                    this@draw.drawContent()
                    canvas.restore()
                }
            }
        }
    }
}

/** Kept so the default can be named where it is provided. */
internal val AloPressFeedbackIndication: Indication = AloPressIndication
