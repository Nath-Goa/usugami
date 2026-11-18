package io.github.nath_goa.usugami.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.ui.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The one press feedback of the app. The theme gives [PressIndication.Row] to every foundation
// clickable, toggleable and selectable that names no indication of its own: rows, tiles and custom
// controls. A Material pressable passes its own ripple and never reads the theme's indication, so it
// gets this feedback only through its kit wrapper, which adds Modifier.indication with the same
// interaction source.

/**
 * How a pressable shows it is pressed: at touch-down at once, fading after release. A keyboard or
 * D-pad focus draws an outline. It has no semantics.
 */
class PressIndication private constructor(
    private val kind: Kind,
    private val shape: Shape,
    private val content: Color,
    private val insetVertical: Dp,
    private val focusInset: Dp = 0.dp,
) : IndicationNodeFactory {
    companion object {
        /** Plain clickables: a `pressTint` fill of the bounds. The theme's default. */
        val Row: PressIndication = PressIndication(Kind.Tint, RectangleShape, Color.Unspecified, 0.dp)

        /**
         * The same fill inside [shape], shrunk by [insetVertical] top and bottom (segments). Its focus
         * outline lies [focusInset] further in: inside the edge of that width a chosen tile or swatch
         * draws, which would hide it.
         */
        fun shaped(shape: Shape, insetVertical: Dp = 0.dp, focusInset: Dp = 0.dp): PressIndication =
            PressIndication(Kind.Tint, shape, Color.Unspecified, insetVertical, focusInset)

        /** Buttons: content and container scale to 0.97, with [content] at 10 % over them inside [shape]. */
        fun button(shape: Shape, content: Color): PressIndication = PressIndication(Kind.Button, shape, content, 0.dp)

        /** Icon buttons: a 40 dp disc of [content] at 10 %, the glyph scales to 0.92. */
        fun glyph(content: Color): PressIndication = PressIndication(Kind.Glyph, CircleShape, content, 0.dp)

        /**
         * An icon button with a fill of its own (PaperGlyphButton): the same disc, drawn OVER the
         * button, whose 40 dp fill would hide it, and scaling with it.
         */
        internal fun glyphOverFill(content: Color): PressIndication = PressIndication(Kind.GlyphOverFill, CircleShape, content, 0.dp)
    }

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource, kind, shape, content, insetVertical, focusInset)

    override fun equals(other: Any?): Boolean =
        other is PressIndication && kind == other.kind && shape == other.shape && content == other.content &&
            insetVertical == other.insetVertical && focusInset == other.focusInset

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + shape.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + insetVertical.hashCode()
        result = 31 * result + focusInset.hashCode()
        return result
    }
}

private enum class Kind { Tint, Button, Glyph, GlyphOverFill }

/** A button's content and container while pressed. */
private const val ButtonScale = 0.97f

/** An icon button's glyph while pressed. */
private const val GlyphScale = 0.92f

/** The disc behind a pressed icon button's glyph. */
private val GlyphDisc = 40.dp

/** The focus outline's width. */
private val FocusWidth = 2.dp

/** How far a button's focus outline lies outside its bounds. */
private val FocusGap = 2.dp

/** The focus circle round an icon button. */
private val GlyphFocus = 46.dp

/** How close a press's scale spring comes to its end before it stops: no jump over so small a range. */
private const val ScaleThreshold = 0.001f

private class PressNode(
    private val source: InteractionSource,
    private val kind: Kind,
    private val shape: Shape,
    private val content: Color,
    private val insetVertical: Dp,
    private val focusInset: Dp,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val tint = Animatable(0f)
    private val scale = Animatable(1f)
    private var pressing: Job? = null
    private var focused = false

    // What is drawn, made again only when the size (or the direction, or the density) changes: no
    // allocation per frame.
    private var outlineSize = Size.Unspecified
    private var outlineDirection = LayoutDirection.Ltr
    private var outlineDensity = 0f
    private var outline: Outline? = null
    private var ring: Outline? = null
    private var stroke: Stroke? = null

    override fun onAttach() {
        coroutineScope.launch {
            // A node used again (in a lazy list) starts at rest.
            tint.snapTo(0f)
            scale.snapTo(1f)
            var down = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        down++
                        pressing = launch {
                            launch { tint.snapTo(1f) }
                            // A threshold of a thousandth: the default hundredth is a third of a
                            // button's travel, and the spring would end in a jump.
                            if (kind != Kind.Tint) {
                                scale.animateTo(if (kind == Kind.Button) ButtonScale else GlyphScale, Motion.press(ScaleThreshold))
                            }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        down = (down - 1).coerceAtLeast(0)
                        if (down == 0) {
                            val press = pressing
                            launch {
                                // The press is seen to land before it fades; not if a new one came meanwhile.
                                press?.join()
                                if (down == 0) {
                                    launch { tint.animateTo(0f, tween(150)) }
                                    if (kind != Kind.Tint) scale.animateTo(1f, Motion.ui(ScaleThreshold))
                                }
                            }
                        }
                    }
                    is FocusInteraction.Focus -> {
                        focused = true
                        invalidateDraw()
                    }
                    is FocusInteraction.Unfocus -> {
                        focused = false
                        invalidateDraw()
                    }
                }
            }
        }
    }

    override fun onDetach() {
        pressing = null
        focused = false
    }

    override fun ContentDrawScope.draw() {
        val alpha = tint.value
        val s = scale.value
        when (kind) {
            Kind.Tint -> {
                drawContent()
                if (alpha > 0f || focused) {
                    val colors = currentValueOf(LocalUsugamiColors)
                    if (shape === RectangleShape && insetVertical == 0.dp && focusInset == 0.dp) {
                        if (alpha > 0f) drawRect(colors.pressTint, alpha = alpha)
                        if (focused) {
                            val half = FocusWidth.toPx() / 2
                            drawRect(colors.focus, Offset(half, half), Size(size.width - 2 * half, size.height - 2 * half), style = stroke())
                        }
                    } else {
                        prepare()
                        val inset = insetVertical.toPx()
                        translate(top = inset) {
                            if (alpha > 0f) drawOutline(outline!!, colors.pressTint, alpha = alpha)
                            // Wholly inside the shape, as the row's outline: a clip at the node's
                            // bounds cuts none of it.
                            if (focused) drawOutline(ring!!, colors.focus, style = stroke!!)
                        }
                    }
                }
            }
            Kind.Button -> {
                prepare()
                if (s != 1f) scale(s) { this@draw.drawButton(alpha) } else drawButton(alpha)
                if (focused) drawOutline(ring!!, currentValueOf(LocalUsugamiColors).focus, style = stroke!!)
            }
            Kind.Glyph -> {
                if (alpha > 0f) drawCircle(content, radius = GlyphDisc.toPx() / 2, alpha = 0.10f * alpha)
                if (s != 1f) scale(s) { this@draw.drawContent() } else drawContent()
                if (focused) drawCircle(currentValueOf(LocalUsugamiColors).focus, radius = GlyphFocus.toPx() / 2, style = stroke())
            }
            Kind.GlyphOverFill -> {
                // Over the button's own fill, and scaling with it, so the disc stays as big as the fill.
                if (s != 1f) scale(s) { this@draw.drawOverFill(alpha) } else drawOverFill(alpha)
                if (focused) drawCircle(currentValueOf(LocalUsugamiColors).focus, radius = GlyphFocus.toPx() / 2, style = stroke())
            }
        }
    }

    /** An icon button with a fill of its own, with the disc over it. */
    private fun ContentDrawScope.drawOverFill(alpha: Float) {
        drawContent()
        if (alpha > 0f) drawCircle(content, radius = GlyphDisc.toPx() / 2, alpha = 0.10f * alpha)
    }

    /** A button's content, with its tint over it inside the shape. */
    private fun ContentDrawScope.drawButton(alpha: Float) {
        drawContent()
        if (alpha > 0f) drawOutline(outline!!, content, alpha = 0.10f * alpha)
    }

    /** The focus outline's stroke, made again only when the density changes. */
    private fun DrawScope.stroke(): Stroke {
        val width = FocusWidth.toPx()
        stroke?.let { if (it.width == width) return it }
        return Stroke(width).also { stroke = it }
    }

    /**
     * The shape's outline for this size, and its focus outline, concentric with it: a button's 2 dp
     * outside its bounds; a shaped tint's half its width (and its focus inset) inside the shape, so it
     * lies wholly inside.
     */
    private fun DrawScope.prepare() {
        val height = if (kind == Kind.Tint) size.height - 2 * insetVertical.toPx() else size.height
        val shown = Size(size.width, height.coerceAtLeast(0f))
        if (outline != null && shown == outlineSize && layoutDirection == outlineDirection && density == outlineDensity) return
        outlineSize = shown
        outlineDirection = layoutDirection
        outlineDensity = density
        outline = shape.createOutline(shown, layoutDirection, this)
        val width = stroke().width
        ring = when (kind) {
            Kind.Button -> shape.concentric(shown, layoutDirection, this, -FocusGap.toPx())
            Kind.Tint -> shape.concentric(shown, layoutDirection, this, width / 2 + focusInset.toPx())
            Kind.Glyph, Kind.GlyphOverFill -> null
        }
    }
}
