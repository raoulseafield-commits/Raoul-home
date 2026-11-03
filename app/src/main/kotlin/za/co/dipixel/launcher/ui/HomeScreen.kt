package za.co.dipixel.launcher.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.NightsStay
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Config
import za.co.dipixel.launcher.data.Show
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.data.Weather
import za.co.dipixel.launcher.data.WeatherNow
import za.co.dipixel.launcher.night.Sleep
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    config: Config,
    apps: List<TvApp>,
    onConfig: (Config) -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current

    // ---- wallpaper cycle ----
    val walls = config.wallpapers.ifEmpty { listOf("wall_dusk") }
    var wallIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(walls, config.wallpaperIntervalSec) {
        while (true) {
            delay(config.wallpaperIntervalSec.coerceAtLeast(10) * 1000L)
            wallIndex = (wallIndex + 1) % walls.size
        }
    }

    // ---- clock ----
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(1000) }
    }

    // ---- weather, refreshed every 20 minutes ----
    var weather by remember { mutableStateOf<WeatherNow?>(null) }
    LaunchedEffect(config.latitude, config.longitude) {
        while (true) {
            weather = Weather.fetch(config.latitude, config.longitude) ?: weather
            delay(20 * 60_000L)
        }
    }

    var nightMenu by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf<TvApp?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { firstFocus.requestFocus() } catch (e: Exception) {} }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = walls[wallIndex % walls.size], animationSpec = tween(1500), label = "wall") { name ->
            val id = remember(name) { context.resources.getIdentifier(name, "drawable", context.packageName) }
            if (id != 0) {
                Image(painterResource(id), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        // soft vignette so text always reads
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x33000000), 0.5f to Color(0x00000000), 1f to Color(0x99000000))
            )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp)) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    config.profiles.forEachIndexed { i, p ->
                        Pill(
                            text = p.name,
                            selected = i == config.activeProfile,
                            onClick = { onConfig(config.copy(activeProfile = i)) },
                            modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (config.sleepAt > 0L) {
                    val mins = ((config.sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    Text("Sleep in ${mins + 1} min", color = Palette.textDim, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                }
                Pill(text = "", icon = Icons.Rounded.Bedtime, selected = config.nightMode, onClick = { nightMenu = !nightMenu })
                Spacer(Modifier.width(8.dp))
                Pill(text = "", icon = Icons.Rounded.Settings, onClick = onOpenSettings)
            }

            // ---- date, weather, clock ----
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    weather?.let { w ->
                        Spacer(Modifier.width(14.dp))
                        Icon(weatherIcon(w), contentDescription = null, tint = Palette.text, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${w.tempC}°  ${w.description}", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                }
                val pattern = (if (config.use24h) "HH:mm" else "h:mm") + (if (config.showSeconds) ":ss" else "")
                Text(
                    now.format(DateTimeFormatter.ofPattern(pattern)),
                    style = TextStyle(
                        brush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF2C6E0))),
                        fontSize = 118.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-4).sp
                    ),
                    maxLines = 1
                )
            }

            Spacer(Modifier.weight(1f))

            // ---- shows row ----
            val profile = config.profiles.getOrNull(config.activeProfile) ?: config.profiles.first()
            SectionTitle("${profile.name.uppercase()}  ·  CONTINUE WATCHING")
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                items(profile.shows) { show -> ShowTile(show, apps, onClick = { Apps.openShow(context, show) }) }
                item {
                    Tile(onClick = onOpenSettings, modifier = Modifier.width(220.dp).height(100.dp), container = Color(0x1FFFFFFF)) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = Palette.textDim, modifier = Modifier.size(28.dp))
                            Text("Add a show", color = Palette.textDim, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- apps row ----
            val favourites = remember(config.favouriteApps, apps) { favouriteApps(config, apps) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                items(favourites, key = { it.packageName }) { app ->
                    AppTile(app, onClick = { Apps.launch(context, app.packageName) }, onLongClick = { appMenu = app })
                }
                item {
                    Tile(onClick = onOpenAllApps, modifier = Modifier.width(150.dp).height(72.dp)) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Palette.text)
                            Spacer(Modifier.width(8.dp))
                            Text("All apps", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // ---- night mode overlay (dim + warm) ----
        if (config.nightMode) {
            Box(Modifier.fillMaxSize().background(Color(0x8C1A0A00)))
        }

        // ---- app menu (hold OK on a home row app) ----
        val menuApp = appMenu
        AnimatedVisibility(visible = menuApp != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
            if (menuApp != null) {
                val order = favourites.map { it.packageName }
                val idx = order.indexOf(menuApp.packageName)
                fun saveOrder(newOrder: List<String>) { onConfig(config.copy(favouriteApps = newOrder)) }
                AppMenu(
                    app = menuApp,
                    canLeft = idx > 0,
                    canRight = idx >= 0 && idx < order.lastIndex,
                    onLeft = { saveOrder(order.toMutableList().apply { add(idx - 1, removeAt(idx)) }) },
                    onRight = { saveOrder(order.toMutableList().apply { add(idx + 1, removeAt(idx)) }) },
                    onUnpin = { saveOrder(order - menuApp.packageName); appMenu = null },
                    onClose = { appMenu = null }
                )
            }
        }

        // ---- night menu ----
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

@Composable
fun ShowTile(show: Show, apps: List<TvApp>, onClick: () -> Unit) {
    val provider = remember(show.providerPackage, apps) { apps.firstOrNull { it.packageName == show.providerPackage } }
    Tile(onClick = onClick, modifier = Modifier.width(220.dp).height(100.dp), container = Color(0xFF1C1C22), focusedContainer = Color(0xFF2A2A33)) {
        Box(Modifier.fillMaxSize()) {
            provider?.banner?.let { DrawableImage(it, Modifier.fillMaxSize(), ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color(0xE6000000)))))
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    provider?.icon?.let { DrawableImage(it, Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))) }
                    Spacer(Modifier.width(6.dp))
                    Text(show.providerLabel.uppercase(), color = Palette.textDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Text(show.title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun AppTile(app: TvApp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Tile(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.width(150.dp).height(72.dp), container = Color(0xFF1C1C22), focusedContainer = Color(0xFF1C1C22)) {
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

@Composable
private fun NightMenu(
    config: Config,
    onClose: () -> Unit,
    onToggleNight: () -> Unit,
    onSleep: (Int) -> Unit,
    onCancelSleep: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) {} }
    Column(
        Modifier
            .padding(top = 84.dp, end = 48.dp)
            .width(260.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE6141418))
            .padding(14.dp),
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
private fun AppMenu(
    app: TvApp,
    canLeft: Boolean,
    canRight: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUnpin: () -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(app.packageName) { try { focus.requestFocus() } catch (e: Exception) {} }
    Column(
        Modifier
            .padding(bottom = 110.dp, end = 48.dp)
            .width(260.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE6141418))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(app.label, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        if (canLeft) MenuRow("Move left", onLeft, Modifier.focusRequester(focus))
        if (canRight) MenuRow("Move right", onRight, if (canLeft) Modifier else Modifier.focusRequester(focus))
        MenuRow("Unpin from home", onUnpin)
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

fun Context.appLabel(pkg: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { pkg }
