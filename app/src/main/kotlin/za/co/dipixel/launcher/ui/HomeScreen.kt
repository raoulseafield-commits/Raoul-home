package za.co.dipixel.launcher.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.NightsStay
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Config
import za.co.dipixel.launcher.data.Show
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.data.Weather
import za.co.dipixel.launcher.data.WeatherNow
import za.co.dipixel.launcher.night.Sleep
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What the big background and title area is showing right now. */
private sealed class Hero {
    data class OfShow(val show: Show, val index: Int) : Hero()
    data class OfApp(val app: TvApp) : Hero()
    data object Empty : Hero()
}

private val profileColors = listOf(Color(0xFFE50914), Color(0xFF2F80ED), Color(0xFFF2C94C), Color(0xFF27AE60), Color(0xFFBB6BD9))

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

    // ---- per app colours, pulled from each app's banner ----
    val appColors = remember { mutableStateMapOf<String, Color>() }
    LaunchedEffect(favourites) {
        withContext(Dispatchers.Default) {
            for (a in favourites) if (a.packageName !in appColors) {
                try {
                    val bmp = (a.banner ?: a.icon).toBitmap(160, 90, Bitmap.Config.ARGB_8888)
                    val p = androidx.palette.graphics.Palette.from(bmp).generate()
                    appColors[a.packageName] = Color(p.getVibrantColor(p.getDominantColor(0xFF333344.toInt())))
                } catch (e: Exception) { }
            }
        }
    }

    var hero by remember { mutableStateOf<Hero>(Hero.Empty) }
    LaunchedEffect(profile.shows, favourites) {
        val h = hero
        val stale = h is Hero.Empty || (h is Hero.OfShow && profile.shows.none { it.title == h.show.title })
        if (stale) hero = profile.shows.firstOrNull()?.let { Hero.OfShow(it, 0) } ?: favourites.firstOrNull()?.let { Hero.OfApp(it) } ?: Hero.Empty
        // keep the hero fresh when artwork arrives for the focused show
        if (h is Hero.OfShow) profile.shows.getOrNull(h.index)?.let { if (it.title == h.show.title && it != h.show) hero = Hero.OfShow(it, h.index) }
    }

    // ---- clock + weather ----
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000) } }
    var weather by remember { mutableStateOf<WeatherNow?>(null) }
    LaunchedEffect(config.latitude, config.longitude) {
        while (true) { weather = Weather.fetch(config.latitude, config.longitude) ?: weather; delay(20 * 60_000L) }
    }

    var nightMenu by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf<TvApp?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); try { firstFocus.requestFocus() } catch (e: Exception) {} }

    val tintTarget = when (val h = hero) {
        is Hero.OfApp -> appColors[h.app.packageName] ?: Color(0xFF26263A)
        else -> Color(0xFF101018)
    }
    val tint by animateColorAsState(tintTarget, tween(700), label = "tint")

    Box(Modifier.fillMaxSize().background(Color(0xFF050507))) {
        HeroBackground(hero, tint)

        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 26.dp, bottom = 18.dp)) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                config.profiles.forEachIndexed { i, p ->
                    val selected = i == config.activeProfile
                    val col = profileColors[i % profileColors.size]
                    Tile(
                        onClick = { onConfig(config.copy(activeProfile = i)) },
                        modifier = Modifier.padding(end = 10.dp).size(if (selected) 44.dp else 36.dp),
                        corner = 22,
                        container = col.copy(alpha = if (selected) 1f else 0.5f),
                        focusedContainer = col,
                        focusedScale = 1.12f
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(p.name.take(1).uppercase(), color = Color.White, fontSize = if (selected) 19.sp else 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(profile.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                if (config.sleepAt > 0L) {
                    val mins = ((config.sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    Text("Sleep in ${mins + 1} min", color = Palette.textDim, fontSize = 13.sp, modifier = Modifier.padding(end = 14.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 16.dp)) {
                    val pattern = (if (config.use24h) "HH:mm" else "h:mm") + (if (config.showSeconds) ":ss" else "")
                    Text(now.format(DateTimeFormatter.ofPattern(pattern)), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(now.format(DateTimeFormatter.ofPattern("EEE d MMM")), color = Palette.textDim, fontSize = 12.sp)
                        weather?.let { w ->
                            Spacer(Modifier.width(8.dp))
                            Icon(weatherIcon(w), contentDescription = null, tint = Palette.textDim, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${w.tempC}°", color = Palette.textDim, fontSize = 12.sp)
                        }
                    }
                }
                Pill(text = "", icon = Icons.Rounded.Bedtime, selected = config.nightMode, onClick = { nightMenu = !nightMenu })
                Spacer(Modifier.width(8.dp))
                Pill(text = "", icon = Icons.Rounded.Settings, onClick = onOpenSettings)
            }

            Spacer(Modifier.weight(1f))

            HeroInfo(hero)

            Spacer(Modifier.height(16.dp))

            // ---- continue watching ----
            SectionTitle(if (profile.shows.isEmpty()) "ADD SOMETHING TO WATCH" else "CONTINUE WATCHING")
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                itemsIndexed(profile.shows, key = { i, s -> s.title + i }) { i, show ->
                    ShowCard(
                        show = show,
                        provider = apps.firstOrNull { it.packageName == show.providerPackage },
                        onClick = { Thread { za.co.dipixel.launcher.hub.Hub.playShow(context, show) }.start() },
                        modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .onFocusChanged { if (it.hasFocus) hero = Hero.OfShow(show, i) }
                    )
                }
                item {
                    Tile(
                        onClick = onOpenSettings,
                        modifier = (if (profile.shows.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier).width(210.dp).height(118.dp),
                        container = Color(0x26FFFFFF)
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = Palette.textDim, modifier = Modifier.size(28.dp))
                            Text("Add a show", color = Palette.textDim, fontSize = 13.sp)
                            Text("or search on Mi Remote", color = Palette.textFaint, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ---- apps ----
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                itemsIndexed(favourites, key = { _, a -> a.packageName }) { _, app ->
                    AppTile(
                        app,
                        onClick = { Apps.launch(context, app.packageName) },
                        onLongClick = { appMenu = app },
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) hero = Hero.OfApp(app) }
                    )
                }
                item {
                    Tile(onClick = onOpenAllApps, modifier = Modifier.width(150.dp).height(84.dp), container = Color(0x33FFFFFF)) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Palette.text)
                            Spacer(Modifier.width(8.dp))
                            Text("All apps", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        if (config.nightMode) Box(Modifier.fillMaxSize().background(Color(0x8C1A0A00)))

        // ---- app menu (hold OK on a home row app) ----
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
                onToggleNight = { onConfig(config.copy(nightMode = !config.nightMode)) },
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

// ------------------------------------------------------------------ hero

@Composable
private fun HeroBackground(hero: Hero, tint: Color) {
    val context = LocalContext.current
    val drift by rememberInfiniteTransition(label = "drift").animateFloat(
        initialValue = 1.0f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "scale"
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.25f), Color.Transparent), center = Offset(1500f, 200f), radius = 1600f)
            )
        )

        Crossfade(targetState = hero, animationSpec = tween(700), label = "hero") { h ->
            when (h) {
                is Hero.OfShow -> {
                    val s = h.show
                    if (s.backdrop.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(s.backdrop).crossfade(true).build(),
                            contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = drift; scaleY = drift }
                        )
                    } else if (s.poster.isNotBlank()) {
                        // poster only (most movies): blurred fill plus the poster itself on the right
                        AsyncImage(
                            model = s.poster, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.7f }
                        )
                        Box(Modifier.fillMaxSize().padding(end = 70.dp, top = 90.dp, bottom = 230.dp), contentAlignment = Alignment.CenterEnd) {
                            AsyncImage(model = s.poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.clip(RoundedCornerShape(12.dp)))
                        }
                    }
                }
                is Hero.OfApp -> {
                    val d = h.app.banner ?: h.app.icon
                    val bmp = remember(h.app.packageName) { d.toBitmap(480, 270).asImageBitmap() }
                    Image(bmp, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = 0.45f })
                    Box(Modifier.fillMaxSize().padding(end = 70.dp, top = 100.dp, bottom = 260.dp), contentAlignment = Alignment.CenterEnd) {
                        Image(bmp, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(380.dp).height(214.dp).clip(RoundedCornerShape(18.dp)))
                    }
                }
                Hero.Empty -> {}
            }
        }

        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0050507), 0.45f to Color(0x99050507), 0.75f to Color(0x00050507))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99050507), 0.18f to Color(0x00050507), 0.5f to Color(0x00050507), 0.78f to Color(0xD9050507), 1f to Color(0xFF050507))))
    }
}

@Composable
private fun HeroInfo(hero: Hero) {
    val shadow = Shadow(Color(0xAA000000), blurRadius = 18f)
    Column(Modifier.width(560.dp)) {
        when (hero) {
            is Hero.OfShow -> {
                val s = hero.show
                Text(
                    if (s.providerLabel.isNotBlank()) "CONTINUE ON ${s.providerLabel.uppercase()}" else "CONTINUE WATCHING",
                    color = Color(0xFFF2C6E0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(s.title, style = TextStyle(color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, shadow = shadow, letterSpacing = (-0.5).sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.meta.isNotBlank()) Text(s.meta, color = Palette.textDim, fontSize = 14.sp, maxLines = 1)
                if (s.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.summary, color = Palette.textDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Press OK to play", color = Palette.textFaint, fontSize = 12.sp)
                }
            }
            is Hero.OfApp -> {
                Text("APP", color = Color(0xFFF2C6E0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(hero.app.label, style = TextStyle(color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Press OK to open  ·  hold OK to move it", color = Palette.textFaint, fontSize = 13.sp)
            }
            Hero.Empty -> Text("Mi TV", style = TextStyle(color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, shadow = shadow))
        }
    }
}

// ------------------------------------------------------------------ rows

@Composable
private fun ShowCard(show: Show, provider: TvApp?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, modifier = modifier.width(210.dp).height(118.dp), container = Color(0xFF1C1C22), focusedContainer = Color(0xFF2A2A33)) {
        Box(Modifier.fillMaxSize()) {
            val img = show.backdrop.ifBlank { show.poster }
            if (img.isNotBlank()) {
                AsyncImage(model = img, contentDescription = show.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                provider?.banner?.let { DrawableImage(it, Modifier.fillMaxSize(), ContentScale.Crop) }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color(0x00000000), 1f to Color(0xE6000000))))
            provider?.icon?.let { DrawableImage(it, Modifier.padding(8.dp).size(20.dp).clip(RoundedCornerShape(5.dp)).align(Alignment.TopStart)) }
            Text(show.title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

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
    Slot(listOf("dstv", "dstv now"), listOf("dstv")),
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
private fun NightMenu(config: Config, onClose: () -> Unit, onToggleNight: () -> Unit, onSleep: (Int) -> Unit, onCancelSleep: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) {} }
    Column(
        Modifier.padding(top = 84.dp, end = 48.dp).width(260.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6141418)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Night", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        MenuRow(if (config.nightMode) "Night mode: on" else "Night mode: off", onToggleNight, Modifier.focusRequester(focus))
        Text("Sleep timer", color = Palette.textFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        listOf(15, 30, 45, 60, 90).forEach { m -> MenuRow("$m minutes", { onSleep(m) }) }
        if (config.sleepAt > 0L) MenuRow("Cancel timer", onCancelSleep)
        MenuRow("Close", onClose)
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, modifier = modifier.fillMaxWidth().height(40.dp), corner = 10, container = Color(0x14FFFFFF), focusedScale = 1.02f) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, color = Palette.text, fontSize = 15.sp)
        }
    }
}

@Composable
private fun AppMenu(app: TvApp, canLeft: Boolean, canRight: Boolean, onLeft: () -> Unit, onRight: () -> Unit, onUnpin: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(app.packageName) { try { focus.requestFocus() } catch (e: Exception) {} }
    Column(
        Modifier.padding(bottom = 110.dp, end = 48.dp).width(260.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6141418)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(app.label, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
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
