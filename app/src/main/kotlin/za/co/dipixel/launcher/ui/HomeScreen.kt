package za.co.dipixel.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import za.co.dipixel.launcher.R
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Config
import za.co.dipixel.launcher.data.Show
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.data.WallCatalog
import za.co.dipixel.launcher.data.Wallpapers
import za.co.dipixel.launcher.data.Weather
import za.co.dipixel.launcher.data.WeatherNow
import za.co.dipixel.launcher.night.Sleep
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val ClockFont = FontFamily(
    Font(R.font.oswald, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300)))
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val UiFont = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

@Composable
fun HomeScreen(
    config: Config,
    apps: List<TvApp>,
    onConfig: (Config) -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val profile = config.profiles.getOrNull(config.activeProfile) ?: config.profiles.first()
    val favourites = remember(config.favouriteApps, apps) { favouriteApps(config, apps) }

    // the show whose backdrop is showing (only while a Continue watching card has focus)
    var heroShow by remember { mutableStateOf<Show?>(null) }
    LaunchedEffect(profile.shows) {
        heroShow?.let { h -> heroShow = profile.shows.firstOrNull { it.title == h.title } }
    }

    // wallpaper rotation
    val walls = config.wallpapers.ifEmpty { Wallpapers.ALL }
    var wallIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(walls, config.wallpaperIntervalSec) {
        while (true) { delay(config.wallpaperIntervalSec.coerceAtLeast(30) * 1000L); wallIndex = (wallIndex + 1) % walls.size }
    }
    val wall = walls[wallIndex % walls.size]

    var weather by remember { mutableStateOf<WeatherNow?>(null) }
    LaunchedEffect(config.latitude, config.longitude) {
        while (true) { weather = Weather.fetch(config.latitude, config.longitude) ?: weather; delay(20 * 60_000L) }
    }

    var nightMenu by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf<TvApp?>(null) }
    val dockFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(250); try { dockFocus.requestFocus() } catch (e: Exception) {} }

    val lockAlpha by animateFloatAsState(if (heroShow == null) 1f else 0f, tween(450), label = "lock")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------- wallpaper with the clock tucked behind the mountain ----------
        Crossfade(targetState = wall, animationSpec = tween(1200), label = "wall") { w ->
            DepthWallpaper(w, config, weather, lockAlpha)
        }

        // ---------- show backdrop (Continue watching focus only) ----------
        Crossfade(targetState = heroShow, animationSpec = tween(450), label = "hero") { s ->
            if (s != null) ShowHero(s)
        }

        // soft shade at the bottom so the rows read on any photo
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color(0x00000000), 1f to Color(0xA6000000))))

        // ---------- top bar ----------
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileToggle(config, onConfig)
            Spacer(Modifier.weight(1f))
            if (config.sleepAt > 0L) {
                val mins = ((config.sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                Text("Sleep in ${mins + 1} min", color = Palette.textDim, fontSize = 13.sp, fontFamily = UiFont, modifier = Modifier.padding(end = 12.dp))
            }
            GlassCircle(Icons.Rounded.Bedtime, selected = config.sleepAt > 0L) { nightMenu = !nightMenu }
        }

        // ---------- rows ----------
        Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(bottom = 14.dp)) {
            if (profile.shows.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus) heroShow = null },
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 10.dp)
                ) {
                    itemsIndexed(profile.shows, key = { i, s -> s.title + i }) { _, show ->
                        ShowCard(
                            show = show,
                            provider = apps.firstOrNull { it.packageName == show.providerPackage },
                            onClick = { Thread { za.co.dipixel.launcher.hub.Hub.playShow(context, show) }.start() },
                            modifier = Modifier.onFocusChanged { if (it.hasFocus) heroShow = show }
                        )
                    }
                }
            } else {
                Text("Shows you watch through Mi TV or Mi Remote appear here", color = Palette.textFaint, fontSize = 13.sp, fontFamily = UiFont,
                    modifier = Modifier.padding(start = 44.dp, bottom = 10.dp))
            }

            // dock
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 10.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(favourites, key = { _, a -> a.packageName }) { i, app ->
                    DockIcon(
                        app = app,
                        onClick = { Apps.launch(context, app.packageName) },
                        onLongClick = { appMenu = app },
                        modifier = if (i == 0) Modifier.focusRequester(dockFocus) else Modifier
                    )
                }
                item {
                    DockGlyph(Icons.Rounded.Apps, onOpenAllApps, if (favourites.isEmpty()) Modifier.focusRequester(dockFocus) else Modifier)
                }
                item {
                    DockGlyph(Icons.Rounded.Palette, {
                        context.startActivity(android.content.Intent(context, za.co.dipixel.launcher.art.AmbientActivity::class.java))
                    })
                }
                item { DockGlyph(Icons.Rounded.Settings, onOpenSettings) }
            }
        }


        // ---------- menus ----------
        val menuApp = appMenu
        AnimatedVisibility(visible = menuApp != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
            if (menuApp != null) {
                val order: List<String> = favourites.map { it.packageName }
                val idx = order.indexOf(menuApp.packageName)
                fun saveOrder(newOrder: List<String>) { onConfig(config.copy(favouriteApps = newOrder.ifEmpty { listOf("none") })) }
                AppMenu(
                    app = menuApp,
                    canLeft = idx > 0,
                    canRight = idx >= 0 && idx < order.lastIndex,
                    onLeft = { val m: MutableList<String> = order.toMutableList(); m.add(idx - 1, m.removeAt(idx)); saveOrder(m) },
                    onRight = { val m: MutableList<String> = order.toMutableList(); m.add(idx + 1, m.removeAt(idx)); saveOrder(m) },
                    onUnpin = { saveOrder(order - menuApp.packageName); appMenu = null },
                    onClose = { appMenu = null }
                )
            }
        }
        AnimatedVisibility(visible = nightMenu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            NightMenu(
                config = config,
                onClose = { nightMenu = false },
                onSleep = { mins ->
                    Sleep.schedule(context, mins)
                    onConfig(config.copy(sleepAt = System.currentTimeMillis() + mins * 60_000L))
                    nightMenu = false
                },
                onCancelSleep = { Sleep.cancel(context); onConfig(config.copy(sleepAt = 0L)); nightMenu = false }
            )
        }
    }
}

// ------------------------------------------------------------------ lock screen

/** Photo, then date and the big clock, then the mountain cut-out on top of the clock (iPhone depth effect). */
@Composable
private fun DepthWallpaper(wallId: String, config: Config, weather: WeatherNow?, lockAlpha: Float) {
    val context = LocalContext.current
    val info = remember(wallId) { WallCatalog.get(context)[wallId] }
    val bgId = remember(wallId) { context.resources.getIdentifier(wallId, "drawable", context.packageName) }
    val fgId = remember(wallId) { context.resources.getIdentifier(wallId + "_fg", "drawable", context.packageName) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth; val h = maxHeight
        if (bgId != 0) Image(painterResource(bgId), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().alpha(lockAlpha)) {
            // date and weather line
            DateLine(weather, Modifier.fillMaxWidth().offset(y = h * 0.105f))
            // the clock: centred in the band 16.5% to 50% of the screen height
            // pink to lilac like the iPhone lock screen, tinted by the photo's own sky
            val c1 = androidx.compose.ui.graphics.lerp(Color(info?.c1 ?: 0xFFFFD6E0.toInt()), Color(0xFFFFB8D2), 0.45f)
            val c2 = androidx.compose.ui.graphics.lerp(Color(info?.c2 ?: 0xFFE6D8FF.toInt()), Color(0xFFC9B6FF), 0.6f)
            Box(Modifier.fillMaxWidth().offset(y = h * 0.165f).height(h * 0.335f), contentAlignment = Alignment.Center) {
                BigClock(config, with(LocalDensity.current) { (h * 0.335f * 1.32f).toSp() }, c1, c2)
            }
            // mountain in front of the clock
            if (fgId != 0 && info != null) {
                val r = info.fg
                Image(
                    painterResource(fgId), contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.offset(x = w * r[0], y = h * r[1]).size(w * (r[2] - r[0]), h * (r[3] - r[1]))
                )
            }
        }
    }
}

@Composable
private fun BigClock(config: Config, size: androidx.compose.ui.unit.TextUnit, c1: Color, c2: Color) {
    // own state so only the clock recomposes, and only when the minute changes
    val pattern = if (config.use24h) "H:mm" else "h:mm"
    var text by remember(pattern) { mutableStateOf(LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern))) }
    LaunchedEffect(pattern) {
        while (true) {
            val t = LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern))
            if (t != text) text = t
            delay(1000)
        }
    }
    Text(
        text,
        modifier = Modifier.wrapContentHeight(unbounded = true),
        style = TextStyle(
            fontFamily = ClockFont, fontWeight = FontWeight.Light, fontSize = size, letterSpacing = (-1).sp,
            brush = Brush.horizontalGradient(listOf(c1.copy(alpha = 0.92f), c2.copy(alpha = 0.92f))),
            textAlign = TextAlign.Center
        ),
        maxLines = 1
    )
}

@Composable
private fun DateLine(weather: WeatherNow?, modifier: Modifier) {
    var date by remember { mutableStateOf(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM"))) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")) } }
    val shadow = Shadow(Color(0x66000000), blurRadius = 10f)
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(date, style = TextStyle(color = Color(0xF2FFFFFF), fontSize = 18.sp, fontFamily = UiFont, fontWeight = FontWeight.SemiBold, shadow = shadow))
        weather?.let { wt ->
            Spacer(Modifier.width(14.dp))
            Icon(weatherIcon(wt), contentDescription = null, tint = Color(0xF2FFFFFF), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("${wt.tempC}°  ${wt.description}", style = TextStyle(color = Color(0xF2FFFFFF), fontSize = 18.sp, fontFamily = UiFont, fontWeight = FontWeight.SemiBold, shadow = shadow))
        }
    }
}

// ------------------------------------------------------------------ show hero

@Composable
private fun ShowHero(s: Show) {
    Box(Modifier.fillMaxSize()) {
        val img = s.backdrop.ifBlank { s.poster }
        if (img.isNotBlank()) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Color(0xFF101018)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6000000), 0.5f to Color(0x80000000), 0.8f to Color(0x00000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x80000000), 0.2f to Color(0x00000000), 0.55f to Color(0x00000000), 1f to Color(0xE6000000))))
        val shadow = Shadow(Color(0xAA000000), blurRadius = 18f)
        Column(Modifier.padding(start = 44.dp, top = 120.dp).width(560.dp)) {
            Text(
                if (s.providerLabel.isNotBlank()) "CONTINUE ON ${s.providerLabel.uppercase()}" else "CONTINUE WATCHING",
                color = Color(0xFFF2C6E0), fontSize = 12.sp, fontFamily = UiFont, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(s.title, style = TextStyle(color = Color.White, fontSize = 46.sp, fontFamily = UiFont, fontWeight = FontWeight.Bold, shadow = shadow, letterSpacing = (-0.5).sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.meta.isNotBlank()) Text(s.meta, color = Palette.textDim, fontSize = 14.sp, fontFamily = UiFont, maxLines = 1)
            if (s.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(s.summary, color = Palette.textDim, fontSize = 14.sp, fontFamily = UiFont, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Press OK to play", color = Palette.textFaint, fontSize = 12.sp, fontFamily = UiFont)
            }
        }
    }
}

// ------------------------------------------------------------------ parts

@Composable
private fun ProfileToggle(config: Config, onConfig: (Config) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x40000000)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(22.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        config.profiles.forEachIndexed { i, p ->
            val sel = i == config.activeProfile
            Tile(
                onClick = { onConfig(config.copy(activeProfile = i)) },
                corner = 18,
                container = if (sel) Color(0xE6FFFFFF) else Color.Transparent,
                focusedContainer = if (sel) Color.White else Color(0x40FFFFFF),
                focusedScale = 1.05f
            ) {
                Text(p.name, color = if (sel) Color(0xFF16161C) else Color(0xD9FFFFFF), fontSize = 14.sp, fontFamily = UiFont,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun GlassCircle(icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Tile(onClick = onClick, modifier = Modifier.size(42.dp), corner = 21,
        container = if (selected) Color(0xE6FFFFFF) else Color(0x40000000), focusedContainer = if (selected) Color.White else Color(0x59FFFFFF), focusedScale = 1.1f) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (selected) Color(0xFF16161C) else Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ShowCard(show: Show, provider: TvApp?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, modifier = modifier.width(210.dp).height(118.dp), corner = 14, container = Color(0xFF1C1C22), focusedContainer = Color(0xFF2A2A33)) {
        Box(Modifier.fillMaxSize()) {
            val img = show.backdrop.ifBlank { show.poster }
            if (img.isNotBlank()) {
                AsyncImage(model = img, contentDescription = show.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                provider?.banner?.let { DrawableImage(it, Modifier.fillMaxSize(), ContentScale.Crop) }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color(0x00000000), 1f to Color(0xE6000000))))
            provider?.icon?.let { DrawableImage(it, Modifier.padding(8.dp).size(20.dp).clip(RoundedCornerShape(5.dp)).align(Alignment.TopStart)) }
            Text(show.title, color = Palette.text, fontSize = 14.sp, fontFamily = UiFont, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun DockIcon(app: TvApp, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, onLongClick = onLongClick, modifier = modifier.size(64.dp), corner = 16,
        container = Color(0xFF1C1C22), focusedContainer = Color(0xFF1C1C22), focusedScale = 1.14f) {
        DrawableImage(app.icon, Modifier.fillMaxSize(), ContentScale.Crop)
    }
}

@Composable
private fun DockGlyph(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, modifier = modifier.size(64.dp), corner = 16, container = Color(0x4D000000), focusedContainer = Color(0x66FFFFFF), focusedScale = 1.14f) {
        Box(Modifier.fillMaxSize().border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

/** Used by the All apps screen. */
@Composable
fun AppTile(app: TvApp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(150.dp).height(84.dp), container = Color(0xFF1C1C22), focusedContainer = Color(0xFF1C1C22)) {
        val banner = app.banner
        if (banner != null) {
            DrawableImage(banner, Modifier.fillMaxSize(), ContentScale.Crop)
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DrawableImage(app.icon, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
                Text(app.label, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------ defaults

/** Default home row when nothing is pinned yet: these apps, in this order, matched by name. */
private class Slot(val exact: List<String>, val contains: List<String> = emptyList())
private val DEFAULT_ROW = listOf(
    Slot(listOf("netflix"), listOf("netflix")),
    Slot(listOf("google search", "search", "google", "google assistant")),
    Slot(listOf("dstv", "dstv now", "dstv stream"), listOf("dstv")),
    Slot(listOf("spotify"), listOf("spotify")),
    Slot(listOf("youtube")),
    Slot(listOf("youtube kids"), listOf("youtube kids"))
)

fun defaultRow(apps: List<TvApp>): List<TvApp> {
    val out = ArrayList<TvApp>()
    for (slot in DEFAULT_ROW) {
        val hit = slot.exact.firstNotNullOfOrNull { k -> apps.firstOrNull { it.label.trim().equals(k, true) && it !in out } }
            ?: slot.contains.firstNotNullOfOrNull { k -> apps.firstOrNull { it.label.contains(k, true) && it !in out } }
        if (hit != null) out += hit
    }
    return out
}

fun favouriteApps(config: Config, apps: List<TvApp>): List<TvApp> {
    if (config.favouriteApps.isEmpty()) return defaultRow(apps).ifEmpty { apps.take(8) }
    val byPkg = apps.associateBy { it.packageName }
    return config.favouriteApps.mapNotNull { byPkg[it] }
}

fun weatherIcon(w: WeatherNow): ImageVector = when (w.code) {
    0, 1 -> if (w.isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    2, 3, 45, 48 -> Icons.Rounded.Cloud
    in 51..67, in 80..82 -> Icons.Rounded.Umbrella
    in 71..77, 85, 86 -> Icons.Rounded.AcUnit
    95, 96, 99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

// ------------------------------------------------------------------ menus

@Composable
private fun NightMenu(config: Config, onClose: () -> Unit, onSleep: (Int) -> Unit, onCancelSleep: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) {} }
    Column(
        Modifier.padding(top = 80.dp, end = 40.dp).width(260.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6141418)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Sleep timer", color = Palette.text, fontSize = 18.sp, fontFamily = UiFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        listOf(15, 30, 45, 60, 90).forEachIndexed { i, m -> MenuRow("$m minutes", { onSleep(m) }, if (i == 0) Modifier.focusRequester(focus) else Modifier) }
        if (config.sleepAt > 0L) MenuRow("Cancel timer", onCancelSleep)
        MenuRow("Close", onClose)
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, modifier = modifier.fillMaxWidth().height(40.dp), corner = 10, container = Color(0x14FFFFFF), focusedScale = 1.02f) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, color = Palette.text, fontSize = 15.sp, fontFamily = UiFont)
        }
    }
}

@Composable
private fun AppMenu(app: TvApp, canLeft: Boolean, canRight: Boolean, onLeft: () -> Unit, onRight: () -> Unit, onUnpin: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(app.packageName) { try { focus.requestFocus() } catch (e: Exception) {} }
    Column(
        Modifier.padding(bottom = 110.dp, end = 40.dp).width(260.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6141418)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(app.label, color = Palette.text, fontSize = 18.sp, fontFamily = UiFont, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        if (canLeft) MenuRow("Move left", onLeft, Modifier.focusRequester(focus))
        if (canRight) MenuRow("Move right", onRight, if (canLeft) Modifier else Modifier.focusRequester(focus))
        MenuRow("Unpin from home", onUnpin, if (!canLeft && !canRight) Modifier.focusRequester(focus) else Modifier)
        MenuRow("Done", onClose)
    }
}

/** Full black screen shown when the sleep timer fires. Any key wakes it. */
@Composable
fun SleepingScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomEnd) {
        Text("Sleeping  ·  press any button", color = Color(0x40FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(32.dp))
    }
}

@Suppress("unused") private val keepBorder = BorderStroke(1.dp, Color.White)
