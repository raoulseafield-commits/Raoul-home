package za.co.dipixel.remote

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

private val Bg = Color(0xFF0B0B10)
private val Card = Color(0xFF17171F)
private val CardHi = Color(0xFF24242E)
private val Accent = Color(0xFFF2C6E0)
private val Dim = Color(0xB3FFFFFF)
private val Faint = Color(0x73FFFFFF)

data class RemoteApp(val pkg: String, val label: String)

class MainActivity : ComponentActivity() {

    private lateinit var hub: HubClient
    private var discovery: Discovery? = null
    private var paired by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var foundHost by mutableStateOf<String?>(null)
    private var lastMessage by mutableStateOf("")
    private var heard by mutableStateOf("")

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            heard = said
            lastMessage = "Working on it..."
            kotlinx.coroutines.MainScope().launch {
                val r = hub.say(said)
                lastMessage = r?.optString("message")?.ifBlank { null } ?: "Couldn't reach the TV"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = HubClient(this)
        paired = !hub.token.isNullOrBlank() && !hub.host.isNullOrBlank()
        foundHost = hub.host
        discovery = Discovery(this) { h -> runOnUiThread { if (foundHost == null || !paired) foundHost = h; if (paired && hub.host != h) hub.host = h } }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
                    if (!paired) PairScreen() else RemoteScreen()
                }
            }
        }
    }

    override fun onStart() { super.onStart(); discovery?.start() }
    override fun onStop() { discovery?.stop(); super.onStop() }

    /** Phone volume buttons change the TV volume while the remote is open. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (paired && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            kotlinx.coroutines.MainScope().launch { hub.key(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "volup" else "voldown") }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-ZA")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Try: play Bluey on YouTube Kids")
        try { speech.launch(i) } catch (e: Exception) { lastMessage = "Voice typing isn't available on this phone" }
    }

    // ------------------------------------------------------------ pairing

    @Composable
    private fun PairScreen() {
        val scope = rememberCoroutineScope()
        var ip by remember { mutableStateOf(foundHost ?: "") }
        var code by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        LaunchedEffect(foundHost) { if (ip.isBlank() && foundHost != null) ip = foundHost!! }

        Column(
            Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(30.dp))
            Text("Dipixel Remote", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Text("Connect to the TV once. On the TV open the gear icon, then Phone remote, and type the 4 digit code here.", color = Dim, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Text(if (foundHost != null) "Found your TV on the Wi-Fi" else "Looking for the TV on your Wi-Fi...", color = if (foundHost != null) Accent else Faint, fontSize = 13.sp)
            Field(ip, "TV address", KeyboardType.Uri) { ip = it.trim() }
            Field(code, "Pairing code", KeyboardType.NumberPassword) { if (it.length <= 4) code = it.filter(Char::isDigit) }
            BigButton("Connect", enabled = ip.isNotBlank() && code.length == 4) {
                status = "Connecting..."
                scope.launch {
                    hub.host = ip
                    status = if (hub.pair(code)) { paired = true; "" } else
                        if (hub.hello() == null) "Can't reach the TV at $ip. Same Wi-Fi as the TV?" else "Wrong code"
                }
            }
            if (status.isNotEmpty()) Text(status, color = Dim, fontSize = 14.sp)
        }
    }

    // ------------------------------------------------------------ remote

    @Composable
    private fun RemoteScreen() {
        val scope = rememberCoroutineScope()
        val view = LocalView.current
        var apps by remember { mutableStateOf<List<RemoteApp>>(emptyList()) }
        val icons = remember { mutableStateMapOf<String, Bitmap>() }
        var typed by remember { mutableStateOf("") }

        fun tap() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        fun key(k: String) { tap(); scope.launch { val r = hub.key(k); connected = r != null } }

        suspend fun loadApps() {
            val r = hub.apps()
            connected = r != null
            if (r?.optInt("_code") == 401) { hub.token = null; paired = false; return }
            val arr = r?.optJSONArray("home") ?: JSONArray()
            apps = (0 until arr.length()).map { RemoteApp(arr.getJSONObject(it).getString("pkg"), arr.getJSONObject(it).getString("label")) }
            for (a in apps) if (a.pkg !in icons) hub.icon(a.pkg)?.let { icons[a.pkg] = it }
        }

        LaunchedEffect(Unit) {
            while (true) { loadApps(); delay(if (connected) 60_000 else 5_000) }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dipixel Remote", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Color(0xFF5BD68A) else Color(0xFFE06C6C)))
                        Spacer(Modifier.width(6.dp))
                        Text(if (connected) "Connected to TV" else "Can't reach the TV", color = Faint, fontSize = 12.sp)
                    }
                }
                SmallIcon(Icons.Rounded.Refresh) { scope.launch { loadApps() } }
                Spacer(Modifier.width(8.dp))
                SmallIcon(Icons.Rounded.LinkOff) { hub.token = null; paired = false }
            }

            // voice
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF3A2456), Color(0xFF5B2F4E))))
                    .clickable { tap(); listen() }
                    .padding(vertical = 22.dp, horizontal = 22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Talk", tint = Color(0xFF3A2456), modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(if (heard.isBlank()) "Tap and talk to the TV" else "“$heard”", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(lastMessage.ifBlank { "Play Inception on Netflix · Open YouTube Kids" }, color = Dim, fontSize = 13.sp, maxLines = 2)
                    }
                }
            }

            // d-pad
            DPad(onKey = { key(it) })

            // controls
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { key("back") }
                RoundButton(Icons.Rounded.Home, "Home") { key("home") }
                RoundButton(Icons.Rounded.PlayArrow, "Play/Pause") { key("playpause") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundButton(Icons.AutoMirrored.Rounded.VolumeDown, "Vol -") { key("voldown") }
                RoundButton(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { key("mute") }
                RoundButton(Icons.AutoMirrored.Rounded.VolumeUp, "Vol +") { key("volup") }
            }

            // apps
            if (apps.isNotEmpty()) {
                Text("APPS", color = Faint, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.fillMaxWidth())
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(apps, key = { it.pkg }) { a ->
                        Column(
                            Modifier.width(120.dp).clip(RoundedCornerShape(14.dp)).background(Card)
                                .clickable { tap(); scope.launch { hub.open(a.pkg) } },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val bmp = icons[a.pkg]
                            Box(Modifier.fillMaxWidth().height(68.dp).background(CardHi), contentAlignment = Alignment.Center) {
                                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = a.label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Text(a.label.take(1), color = Color.White, fontSize = 26.sp)
                            }
                            Text(a.label, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }

            // type to TV
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(typed, "Type on the TV (search boxes)", KeyboardType.Text) { typed = it } }
                Spacer(Modifier.width(10.dp))
                SmallIcon(Icons.AutoMirrored.Rounded.Send) {
                    if (typed.isNotBlank()) { val t = typed; typed = ""; scope.launch { hub.text(t) } }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // ------------------------------------------------------------ parts

    @Composable
    private fun DPad(onKey: (String) -> Unit) {
        Box(
            Modifier.size(250.dp).clip(CircleShape).background(Card).border(1.dp, Color(0x22FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ArrowHit(Icons.Rounded.KeyboardArrowUp, Modifier.align(Alignment.TopCenter)) { onKey("up") }
            ArrowHit(Icons.Rounded.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter)) { onKey("down") }
            ArrowHit(Icons.Rounded.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart)) { onKey("left") }
            ArrowHit(Icons.Rounded.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd)) { onKey("right") }
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(CardHi).clickable { onKey("ok") },
                contentAlignment = Alignment.Center
            ) { Text("OK", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    @Composable
    private fun ArrowHit(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
        Box(modifier.size(76.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }

    @Composable
    private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(68.dp).clip(CircleShape).background(Card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Text(label, color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun SmallIcon(icon: ImageVector, onClick: () -> Unit) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }

    @Composable
    private fun Field(value: String, label: String, type: KeyboardType, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Accent, unfocusedBorderColor = Color(0x44FFFFFF),
                focusedLabelColor = Accent, unfocusedLabelColor = Faint, cursorColor = Accent
            )
        )
    }

    @Composable
    private fun BigButton(text: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp))
                .background(if (enabled) Accent else Color(0x33FFFFFF))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(text, color = if (enabled) Color(0xFF1A1020) else Faint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
    }
}
