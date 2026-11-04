package za.co.dipixel.launcher.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface

object Palette {
    val glass = Color(0x33FFFFFF)
    val glassStrong = Color(0x59FFFFFF)
    val ink = Color(0xFF101014)
    val text = Color(0xFFFFFFFF)
    val textDim = Color(0xCCFFFFFF)
    val textFaint = Color(0x99FFFFFF)
}

/** Standard focusable tile. Scales when focused and gets the thin rotating, pulsing gradient ring. */
@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    corner: Int = 14,
    container: Color = Palette.glass,
    focusedContainer: Color = Palette.glassStrong,
    focusedScale: Float = 1.08f,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Palette.text,
            focusedContainerColor = focusedContainer,
            focusedContentColor = Palette.text,
            pressedContainerColor = focusedContainer,
            pressedContentColor = Palette.text
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color(0x667B4FD0), elevation = 14.dp)
        )
    ) {
        content()
        if (focused) FocusRing(corner, Modifier.matchParentSize())
    }
}

/** The Mi Remote ambient gradient: violet, pink, blush. */
val RingColors = listOf(Color(0xFF7B4FD0), Color(0xFFE0609B), Color(0xFFF2C6E0), Color(0xFFB38CFF), Color(0xFF7B4FD0))

/** Thin gradient outline that slowly turns and breathes. Only runs on the one focused item. */
@Composable
fun FocusRing(corner: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ring")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "angle")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val w = (1.5f + 0.9f * pulse).dp.toPx()
        val rad = Math.toRadians(angle.toDouble())
        val r = size.maxDimension / 2f
        val c = center
        val dx = (kotlin.math.cos(rad) * r).toFloat(); val dy = (kotlin.math.sin(rad) * r).toFloat()
        val brush = Brush.linearGradient(RingColors, start = Offset(c.x - dx, c.y - dy), end = Offset(c.x + dx, c.y + dy))
        val cr = corner.dp.toPx()
        drawRoundRect(
            brush = brush, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(cr - w / 2, cr - w / 2), style = Stroke(width = w), alpha = 0.7f + 0.3f * pulse
        )
    }
}

/** Pill button with optional icon, used for profile chips and the top right controls. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    textSize: Int = 16
) {
    Tile(
        onClick = onClick,
        modifier = modifier,
        corner = 24,
        container = if (selected) Color.White else Palette.glass,
        focusedContainer = if (selected) Color.White else Palette.glassStrong,
        focusedScale = 1.06f
    ) {
        val color = if (selected) Palette.ink else Palette.text
        Row(
            modifier = Modifier.padding(PaddingValues(horizontal = 18.dp, vertical = 10.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((textSize + 6).dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) {
                Text(text, color = color, fontSize = textSize.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Renders an Android Drawable (app icon or banner) inside Compose. */
@Composable
fun DrawableImage(drawable: Drawable, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val bitmap = remember(drawable) {
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceAtMost(640) else 192
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceAtMost(360) else 192
        drawable.toBitmap(w, h).asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = Palette.textDim,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
    )
}

@Composable
fun EmptyBox(modifier: Modifier = Modifier) { Box(modifier) }
