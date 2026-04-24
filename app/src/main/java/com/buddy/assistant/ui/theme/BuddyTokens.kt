package com.buddy.assistant.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single source of truth for Buddy's design tokens.
 * Mirrors `04 · Foundations` + the iris orb palette used across the Figma system.
 */
object BuddyColors {
    // Surfaces
    val Bg = Color(0xFF0A0A1A)          // BG0 — deep near-black
    val BgMid = Color(0xFF0D0D2B)       // BG0 midtone used in bg vertical gradient
    val Surface = Color(0xFF1C1C3D)     // SURF — card/surface base
    val SurfaceHi = Color(0xFF2A2A52)   // elevated surface (hover/pressed)

    // Ink
    val Ink = Color(0xFFFFFFFF)
    val InkDim = Color(0xFFC9C9E8)      // TXT_DIM
    val InkMute = Color(0xFF8787A8)     // TXT_MUTE
    val InkGhost = Color(0x33FFFFFF)    // ~20% white hairlines/dividers

    // Aurora palette (iris)
    val AuroraBlue = Color(0xFF7B9CFF)
    val AuroraLavender = Color(0xFFB78BFF)
    val AuroraPink = Color(0xFFFF8BC1)
    val AuroraPeach = Color(0xFFFFC383)

    // Interactive + semantic
    val Accent = Color(0xFF6C63FF)      // ACC — primary interactive (focus, chip)
    val Success = Color(0xFF6BCB77)     // OK
    val Error = Color(0xFFFF6B6B)       // ERR
    val Warning = Color(0xFFFFD93D)     // user-voice / warning highlight

    // Core orb inner shadow deep tint
    val OrbShadowDeep = Color(0xFF200040)

    /**
     * Midpoint between AuroraLavender and AuroraPink — used for the button
     * halo so the glow harmonizes with the orb's warm-cool center rather
     * than reading as pure cool lavender or pure warm pink.
     */
    val IrisMid = Color(0xFFDB8BE0)
}

object BuddyGradients {
    /** Full-screen vertical background used on every screen. */
    val Background = Brush.verticalGradient(
        colors = listOf(BuddyColors.Bg, BuddyColors.BgMid, BuddyColors.Bg)
    )

    /**
     * The iris horizontal gradient — lavender → pink → peach (3 stops, Figma spec).
     * Auto-spans the full width of whatever shape it fills, so all three colors
     * are always visible — no truncation regardless of button size.
     * Used on every pill CTA, chip, and orb. (The orb draws its own linear
     * diagonal variant in `BuddyOrb.kt`.)
     */
    fun iris(): Brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to BuddyColors.AuroraBlue,
            0.6f to BuddyColors.AuroraPink,
            1f to BuddyColors.AuroraPeach,
        )
    )

    /** Horizontal overlay pill gradient (deep violet → indigo). */
    val OverlayPill = Brush.horizontalGradient(
        colors = listOf(Color(0xFF0D0D2B), Color(0xFF1A1A3E))
    )

    /**
     * "Liquid glass" button surface — layered translucent whites over the
     * dark app background, simulating frosted glass without a true
     * backdrop blur. Compose doesn't expose a backdrop-blur modifier, so
     * the effect comes from:
     *  - Layer 1: vertical white gradient 0.22 → 0.08 → 0.14 (body)
     *  - Layer 2 (`GlassRim`): bright top + bottom rim highlight for refraction
     *
     * Stacking these two gives the pill enough luminance to read as glass
     * even without blurred content behind it.
     */
    val GlassSurface = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = 0.22f),
            0.5f to Color.White.copy(alpha = 0.08f),
            1f to Color.White.copy(alpha = 0.14f),
        ),
    )

    /**
     * Top + bottom rim highlight overlaid on `GlassSurface`. The strong
     * band at the very top and the subtle band at the very bottom are
     * what sell the "curved glass catching light" illusion.
     */
    val GlassRim = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = 0.35f),
            0.12f to Color.White.copy(alpha = 0.04f),
            0.88f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.18f),
        ),
    )

    /** Silver gradient for brand text — white top → gray bottom (DS 2.0 brand spec). */
    val SilverText: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color(0xFFFAFBFD),
            0.35f to Color(0xFFE8EBF0),
            0.70f to Color(0xFFAEB3BE),
            1f to Color(0xFF6F7580),
        )
    )

    /** DS 2.0 overlay dock: vertical gradient (top-lit dark metal). */
    val DockPill: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color(0xFF2E2F37),
            0.55f to Color(0xFF1C1D22),
            1f to Color(0xFF101114),
        )
    )

    /** Inner shadow bevel for dock pill — bright 1px top edge, dark 1px bottom. */
    val DockInnerShadow: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = 0.30f),
            0.02f to Color.Transparent,
            0.98f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.50f),
        )
    )

    /** Card surface with a hint of warmth — subtle aurora glow on top. */
    fun auroraHalo(centerX: Float, centerY: Float, radius: Float): Brush =
        Brush.radialGradient(
            colors = listOf(
                BuddyColors.AuroraLavender.copy(alpha = 0.25f),
                BuddyColors.AuroraPink.copy(alpha = 0.1f),
                Color.Transparent,
            ),
            center = Offset(centerX, centerY),
            radius = radius,
        )
}

object BuddyShapes {
    val Pill = RoundedCornerShape(50)
    val Card = RoundedCornerShape(20.dp)
    val CardLg = RoundedCornerShape(24.dp)
    val Field = RoundedCornerShape(12.dp)
    val Chip = RoundedCornerShape(12.dp)
    val Button = RoundedCornerShape(14.dp)
    val Overlay = RoundedCornerShape(28.dp)
    val DockPill = RoundedCornerShape(9999.dp)
}

/**
 * Soft iris halo drawn *behind* primary CTAs. Uses `Modifier.blur` with
 * `BlurredEdgeTreatment.Unbounded` so the blurred pixels bleed past the
 * layout bounds — producing a real Gaussian halo with no visible banding
 * (unlike stacked concentric rounded rects, which always show rings).
 *
 * Place this as the first child of a `Box`, then put the button on top:
 * ```
 * Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
 *     IrisHalo()
 *     Box(Modifier.fillMaxWidth().clip(Pill).background(iris())) { ... }
 * }
 * ```
 *
 * Requires API 31+ for blur. On older devices it renders as a plain tinted
 * pill (no halo). minSdk is 29 — most users are on 31+ anyway.
 */
@Composable
fun BoxScope.IrisHalo(
    shape: Shape = BuddyShapes.Pill,
    blurRadius: Dp = 18.dp,
    intensity: Float = 0.65f,
) {
    Box(
        Modifier
            .matchParentSize()
            .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        BuddyColors.AuroraBlue.copy(alpha = 0.75f * intensity),
                        BuddyColors.AuroraPink.copy(alpha = 0.85f * intensity),
                        BuddyColors.AuroraPeach.copy(alpha = 0.6f * intensity),
                    )
                )
            )
    )
}

/**
 * Applies the full liquid-glass stack (clip → frosted body → rim highlight
 * → hairline white border) to any shape. Use this on smaller UI elements
 * (mic FAB, chips, toggle track) to keep them consistent with the primary
 * liquid-glass `IrisButton`.
 *
 * Usage:
 * ```
 * Modifier.size(52.dp).liquidGlass(CircleShape)
 * Modifier.fillMaxWidth().liquidGlass(BuddyShapes.Pill, enabled = selected)
 * ```
 */
/** DS 2.0 dock pill style: gradient fill + inner shadow bevel + border. */
fun Modifier.dockPillStyle(shape: Shape = BuddyShapes.DockPill): Modifier = this
    .clip(shape)
    .background(BuddyGradients.DockPill)
    .background(BuddyGradients.DockInnerShadow)
    .border(1.dp, Color.White.copy(alpha = 0.10f), shape)

/**
 * OmniButton — DS 2.0 primary CTA. Dark metal pill with inner-shadow bevel +
 * 8% white hairline border + subtle drop shadow. Text inside is rendered in
 * silver gradient (use `BuddyGradients.SilverText` brush) by the caller.
 *
 * Figma overlay specs drove the body:
 *   fill #0E0E13 α.95 (approximated by the DockPill gradient)
 *   border 1px white α.08
 *   shadow 0 6 30 rgba(0,0,0,.5)
 *   inner highlight inset 0 1 2 rgba(255,255,255,.06)
 *
 * The caller supplies the text style / content — typically:
 *   Text("Get started", style = TextStyle(brush = BuddyGradients.SilverText, …))
 */
@Composable
fun OmniButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
    shape: Shape = BuddyShapes.Pill,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .shadow(
                elevation = if (enabled) 12.dp else 0.dp,
                shape = shape,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .dockPillStyle(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.4f)),
                enabled = enabled,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * OmniIconButton — circular dark-metal FAB. Used for the mic/send button
 * in the type-command bar and any other icon-only action that should match
 * the primary CTA language without carrying text.
 */
@Composable
fun OmniIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = if (enabled) 10.dp else 0.dp,
                shape = CircleShape,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .dockPillStyle(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.4f), bounded = false),
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * OmniToggle — DS 2.0 switch. When checked, the track uses the dock-pill
 * gradient (dark metal) with the inner bevel, matching the primary CTAs.
 * When unchecked, the track is a flat translucent surface with a hairline
 * border. The knob is a solid white circle with a soft drop shadow.
 *
 * Dimensions mirror iOS/material toggles: 52×32 track, 26 knob, 3 padding.
 */
@Composable
fun OmniToggle(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackW = 52.dp
    val trackH = 32.dp
    val knobSize = 26.dp
    val pad = 3.dp
    val knobOffset by animateDpAsState(
        targetValue = if (checked) trackW - knobSize - pad * 2 else 0.dp,
        animationSpec = tween(200),
        label = "omni-toggle-knob",
    )
    Box(
        modifier = modifier
            .size(width = trackW, height = trackH)
            .then(
                if (checked) {
                    Modifier.dockPillStyle(BuddyShapes.Pill)
                } else {
                    Modifier
                        .clip(BuddyShapes.Pill)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), BuddyShapes.Pill)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) }
            .padding(pad),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .size(knobSize)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

fun Modifier.liquidGlass(shape: Shape, enabled: Boolean = true): Modifier = this
    .clip(shape)
    .background(if (enabled) BuddyGradients.GlassSurface else DisabledGlassSurface)
    .background(if (enabled) BuddyGradients.GlassRim else DisabledGlassRim)
    .border(
        width = 1.dp,
        color = Color.White.copy(alpha = if (enabled) 0.3f else 0.1f),
        shape = shape,
    )

/**
 * Soft single-color halo drawn behind the liquid glass button. Uses
 * `IrisMid` — the midpoint between lavender and pink — so the glow
 * harmonizes with the orb's warm-cool center instead of skewing cool.
 *
 * Not a rainbow gradient; just one tinted bloom blurred past the button's
 * layout bounds via `BlurredEdgeTreatment.Unbounded`.
 */
@Composable
fun BoxScope.OrbHalo(
    shape: Shape = BuddyShapes.Pill,
    blurRadius: Dp = 24.dp,
    intensity: Float = 0.55f,
) {
    Box(
        Modifier
            .matchParentSize()
            .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
            .clip(shape)
            .background(BuddyColors.IrisMid.copy(alpha = intensity))
    )
}

/**
 * "Liquid glass" primary button — the Figma variant F. Translucent frosted
 * pill with refracted rim highlights, iOS 26 style.
 *
 * Stack (bottom → top):
 *  1. Soft orb-tinted halo (`OrbHalo`) — bleeds past layout bounds
 *  2. Drop shadow for lift (`Modifier.shadow`)
 *  3. Frosted glass body (`GlassSurface`) — layered translucent whites
 *  4. Rim highlight (`GlassRim`) — bright top + subtle bottom edge
 *  5. 1dp white border at 30% alpha — the hard edge of the glass
 *  6. Content
 *
 * Compose has no backdrop-blur, so the glass luminance comes from the
 * layered whites rather than from actually blurring the content behind.
 * The effect still reads as glass because the button sits on the dark
 * app background, and the rim highlights sell the curved refraction.
 */
@Composable
fun IrisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
    shape: Shape = BuddyShapes.Pill,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    haloBlur: Dp = 36.dp,
    haloIntensity: Float = 0.5f,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            OrbHalo(shape = shape, blurRadius = haloBlur, intensity = haloIntensity)
        }
        Box(
            modifier = Modifier
                .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                .shadow(
                    elevation = if (enabled) 10.dp else 0.dp,
                    shape = shape,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
                .clip(shape)
                .background(
                    if (enabled) BuddyGradients.GlassSurface
                    else DisabledGlassSurface
                )
                .background(
                    if (enabled) BuddyGradients.GlassRim else DisabledGlassRim
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = if (enabled) 0.3f else 0.1f),
                    shape = shape,
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = BuddyColors.AuroraLavender),
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private val DisabledGlassSurface = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.06f),
        Color.White.copy(alpha = 0.03f),
    )
)
private val DisabledGlassRim = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.White.copy(alpha = 0.08f),
        0.5f to Color.Transparent,
    ),
)

/**
 * Paints a composable (typically an `Icon`) with the given brush using a
 * `SrcAtop` blend so the brush shows only where the content's alpha is > 0.
 * Requires offscreen compositing for the blend mode to apply correctly.
 */
fun Modifier.brushTint(brush: Brush): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
    }

/**
 * Icon variant that fills its vector with a gradient brush (defaults to the
 * DS 2.0 silver brand gradient) instead of a flat tint color.
 */
@Composable
fun GradientIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    brush: Brush = BuddyGradients.SilverText,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = modifier.brushTint(brush),
    )
}

object BuddyTextMetrics {
    // Letter-spacing used for lapidary/display caps — converted from Figma PIXELS to sp-scaled
    val CapsTightSp = 3.sp
    val CapsWideSp = 6.sp
    val DisplayLetterSp = 4.sp
}
