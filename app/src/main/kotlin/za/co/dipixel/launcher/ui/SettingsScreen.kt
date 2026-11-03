package za.co.dipixel.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Config
import za.co.dipixel.launcher.data.Profile
import za.co.dipixel.launcher.data.Show
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.data.Wallpapers

private val sections = listOf("Profiles & shows", "Apps row", "Wallpapers", "Clock & weather", "Phone remote", "System")

@Composable
fun SettingsScreen(config: Config, apps: List<TvApp>, onConfig: (Config) -> Unit) {
    var section by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize().background(Color(0xFF0E0E12))) {
        // left rail
        Column(Modifier.width(280.dp).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
            sections.forEachIndexed { i, s ->
                Tile(onClick = { section = i }, modifier = Modifier.fillMaxWidth().height(46.dp), corner = 12,
                    container = if (i == section) Color.White else Color(0x14FFFFFF),
                    focusedContainer = if (i == section) Color.White else Color(0x33FFFFFF), focusedScale = 1.02f) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                        Text(s, color = if (i == section) Palette.ink else Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        // content
        Box(Modifier.fillMaxSize().padding(end = 48.dp, top = 32.dp, bottom = 32.dp)) {
            when (section) {
                0 -> ProfilesSection(config, apps, onConfig)
                1 -> AppsSection(config, apps, onConfig)
                2 -> WallpapersSection(config, onConfig)
                3 -> ClockSection(config, onConfig)
                4 -> PhoneRemoteSection()
                else -> SystemSection()
            }
        }
    }
}

@Composable
private fun Field(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
            focusedBorderColor = Color.White, unfocusedBorderColor = Color(0x66FFFFFF),
            focusedLabelColor = Palette.textDim, unfocusedLabelColor = Palette.textFaint,
            cursorColor = Color.White
        )
    )
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Tile(onClick = onClick, modifier = modifier.height(44.dp), corner = 12,
        container = if (primary) Color.White else Color(0x1FFFFFFF),
        focusedContainer = if (primary) Color.White else Color(0x40FFFFFF), focusedScale = 1.03f) {
        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (primary) Palette.ink else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit, sub: String? = null) {
    Tile(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(if (sub == null) 48.dp else 60.dp), corner = 12, container = Color(0x14FFFFFF), focusedScale = 1.01f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (checked) Color.White else Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(label, color = Palette.text, fontSize = 16.sp)
                if (sub != null) Text(sub, color = Palette.textFaint, fontSize = 12.sp)
            }
        }
    }
}

// ---------------- Profiles & shows ----------------

@Composable
private fun ProfilesSection(config: Config, apps: List<TvApp>, onConfig: (Config) -> Unit) {
    var selected by remember { mutableIntStateOf(config.activeProfile) }
    val profile = config.profiles.getOrNull(selected) ?: config.profiles.first()

    fun replaceProfile(p: Profile) {
        onConfig(config.copy(profiles = config.profiles.mapIndexed { i, old -> if (i == selected) p else old }))
    }

    var title by remember(selected) { mutableStateOf("") }
    var url by remember(selected) { mutableStateOf("") }
    var providerPkg by remember(selected) { mutableStateOf(apps.firstOrNull()?.packageName ?: "") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            SectionTitle("PROFILE")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                config.profiles.forEachIndexed { i, p -> Pill(p.name, selected = i == selected, onClick = { selected = i }) }
            }
        }
        item {
            Field(profile.name, "Profile name", { replaceProfile(profile.copy(name = it)) }, Modifier.width(360.dp))
        }
        item {
            SectionTitle("SHOWS ON THIS PROFILE")
            Spacer(Modifier.height(4.dp))
            if (profile.shows.isEmpty()) Text("No shows yet. Add one below.", color = Palette.textFaint, fontSize = 14.sp)
        }
        items(profile.shows) { show ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(520.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(14.dp)) {
                    Column {
                        Text(show.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(show.providerLabel + if (show.url.isNotBlank()) "  ·  " + show.url else "", color = Palette.textFaint, fontSize = 12.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Tile(onClick = { replaceProfile(profile.copy(shows = profile.shows - show)) }, modifier = Modifier.size(44.dp), corner = 12, container = Color(0x1FFFFFFF)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = Palette.text) }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("ADD A SHOW")
            Spacer(Modifier.height(8.dp))
            Field(title, "Show title (e.g. Slow Horses)", { title = it }, Modifier.width(520.dp))
            Spacer(Modifier.height(8.dp))
            Field(url, "Link (optional, e.g. https://www.netflix.com/title/80014749)", { url = it }, Modifier.width(520.dp))
            Spacer(Modifier.height(10.dp))
            Text("Opens with", color = Palette.textFaint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(apps, key = { it.packageName }) { app -> Pill(app.label, selected = app.packageName == providerPkg, onClick = { providerPkg = app.packageName }, textSize = 14) }
            }
            Spacer(Modifier.height(10.dp))
            ActionButton("Add show", primary = true, onClick = {
                if (title.isNotBlank()) {
                    val provider = apps.firstOrNull { it.packageName == providerPkg }
                    replaceProfile(profile.copy(shows = profile.shows + Show(title.trim(), providerPkg, provider?.label ?: "", url.trim())))
                    title = ""; url = ""
                }
            })
            Spacer(Modifier.height(12.dp))
            Text(
                "Tip: a link jumps straight into the show. Netflix: netflix.com/title/ID. YouTube: youtube.com/watch?v=ID. " +
                    "Prime: primevideo.com/detail/ID. Disney+, Showmax, DStv and Apple TV share links from their apps also work. " +
                    "Leave it blank and the app simply opens.",
                color = Palette.textFaint, fontSize = 12.sp
            )
        }
    }
}

// ---------------- Apps row ----------------

@Composable
private fun AppsSection(config: Config, apps: List<TvApp>, onConfig: (Config) -> Unit) {
    val current = favouriteApps(config, apps).map { it.packageName }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            SectionTitle("PINNED TO THE HOME ROW, IN ORDER")
            Text("Tick apps in the order you want them. Untick to remove.", color = Palette.textFaint, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(apps, key = { it.packageName }) { app ->
            val idx = current.indexOf(app.packageName)
            CheckRow(
                label = if (idx >= 0) "${idx + 1}.  ${app.label}" else app.label,
                checked = idx >= 0,
                onToggle = {
                    val next = if (idx >= 0) current - app.packageName else current + app.packageName
                    onConfig(config.copy(favouriteApps = next))
                }
            )
        }
    }
}

// ---------------- Wallpapers ----------------

@Composable
private fun WallpapersSection(config: Config, onConfig: (Config) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SectionTitle("SCENES IN ROTATION") }
        items(Wallpapers.ALL) { id ->
            val on = id in config.wallpapers
            CheckRow(Wallpapers.LABELS[id] ?: id, on, onToggle = {
                val next = if (on) config.wallpapers - id else config.wallpapers + id
                if (next.isNotEmpty()) onConfig(config.copy(wallpapers = next))
            })
        }
        item {
            Spacer(Modifier.height(12.dp))
            SectionTitle("CHANGE EVERY")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "1 min", 300 to "5 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 hour").forEach { (sec, label) ->
                    Pill(label, selected = config.wallpaperIntervalSec == sec, onClick = { onConfig(config.copy(wallpaperIntervalSec = sec)) })
                }
            }
        }
    }
}

// ---------------- Clock & weather ----------------

@Composable
private fun ClockSection(config: Config, onConfig: (Config) -> Unit) {
    var lat by remember { mutableStateOf(config.latitude.toString()) }
    var lon by remember { mutableStateOf(config.longitude.toString()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SectionTitle("CLOCK") }
        item { CheckRow("24 hour clock", config.use24h, { onConfig(config.copy(use24h = !config.use24h)) }) }
        item { CheckRow("Show seconds", config.showSeconds, { onConfig(config.copy(showSeconds = !config.showSeconds)) }) }
        item {
            Spacer(Modifier.height(12.dp))
            SectionTitle("WEATHER LOCATION")
            Text("Weather comes from Open-Meteo, no account needed. Default is Pretoria.", color = Palette.textFaint, fontSize = 13.sp)
        }
        item { Field(config.cityName, "City name (label only)", { onConfig(config.copy(cityName = it)) }, Modifier.width(360.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(lat, "Latitude", { lat = it }, Modifier.width(200.dp))
                Field(lon, "Longitude", { lon = it }, Modifier.width(200.dp))
                ActionButton("Apply", primary = true, modifier = Modifier.padding(top = 6.dp), onClick = {
                    val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
                    if (la != null && lo != null) onConfig(config.copy(latitude = la, longitude = lo))
                })
            }
        }
    }
}

// ---------------- Phone remote ----------------

@Composable
private fun PhoneRemoteSection() {
    val context = LocalContext.current
    var code by remember { mutableStateOf(za.co.dipixel.launcher.hub.Pairing.code(context)) }
    val ip = remember { za.co.dipixel.launcher.hub.Pairing.localIp() }
    val paired = remember(code) { za.co.dipixel.launcher.hub.Pairing.pairedCount(context) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SectionTitle("PAIRING CODE") }
        item { Text(code, color = Palette.text, fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = 12.sp) }
        item { Text("Open Dipixel Remote on a phone on the same Wi-Fi and enter this code once.", color = Palette.textDim, fontSize = 15.sp) }
        item { Text("TV address: $ip  ·  phones paired: $paired", color = Palette.textFaint, fontSize = 13.sp) }
        item {
            Spacer(Modifier.height(8.dp))
            ActionButton("New code (unpairs all phones)", { code = za.co.dipixel.launcher.hub.Pairing.newCode(context) }, Modifier.width(360.dp))
        }
        item {
            Spacer(Modifier.height(16.dp))
            SectionTitle("GET THE PHONE APP")
            Text("On each phone open: github.com/raoulseafield-commits/Raoul-home/releases", color = Palette.textDim, fontSize = 14.sp)
            Text("Download remote.apk and install it.", color = Palette.textFaint, fontSize = 13.sp)
        }
    }
}

// ---------------- System ----------------

@Composable
private fun SystemSection() {
    val context = LocalContext.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SectionTitle("SCREENSAVER") }
        item {
            var status by remember { mutableStateOf("Paintings saved on the box: ${za.co.dipixel.launcher.art.ArtRepo.cachedCount(context)} (plus the built-in set)") }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(status, color = Palette.textFaint, fontSize = 13.sp)
                ActionButton("Download new paintings now", {
                    status = "Downloading..."
                    za.co.dipixel.launcher.art.ArtRepo.refreshNow(context) { n ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            status = if (n > 0) "Added $n paintings. Saved on the box: ${za.co.dipixel.launcher.art.ArtRepo.cachedCount(context)}" else "No new paintings (offline?)"
                        }
                    }
                }, Modifier.width(360.dp))
                ActionButton("Screensaver settings (pick Dipixel Art, set the delay)", {
                    try { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Apps.openSystemSettings(context) }
                }, Modifier.width(560.dp))
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { SectionTitle("ANDROID") }
        item { ActionButton("Open Android TV settings", { Apps.openSystemSettings(context) }, Modifier.width(360.dp)) }
        item { ActionButton("Choose default home app", { Apps.openHomeChooser(context) }, Modifier.width(360.dp)) }
        item {
            ActionButton("Allow display over other apps (needed for the sleep timer)", {
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(i) } catch (e: Exception) { Apps.openSystemSettings(context) }
            }, Modifier.width(560.dp))
        }
        if (Build.VERSION.SDK_INT >= 31) item {
            ActionButton("Allow exact alarms (sleep timer accuracy)", {
                val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(i) } catch (e: Exception) { Apps.openSystemSettings(context) }
            }, Modifier.width(560.dp))
        }
        item {
            Spacer(Modifier.height(16.dp))
            SectionTitle("ABOUT")
            Text("Dipixel Home for Xiaomi Box 4K · no ads, no tracking, no accounts.", color = Palette.textFaint, fontSize = 13.sp)
            Text("Press Back to return home.", color = Palette.textFaint, fontSize = 13.sp)
        }
    }
}
