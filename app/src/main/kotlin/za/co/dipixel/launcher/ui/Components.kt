package za.co.dipixel.launcher.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
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

/** Standard focusable tile. Scales and gets a white ring when focused, like tvOS. */
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
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
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
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = 0.dp, shape = shape)
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color(0x80000000), elevation = 12.dp)
        ),
        content = content
    )
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
