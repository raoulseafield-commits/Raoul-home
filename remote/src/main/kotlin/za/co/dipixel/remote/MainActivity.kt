package za.co.dipixel.remote

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private val Bg = Color(0xFF07070B)
private val Card = Color(0xFF15151D)
private val CardHi = Color(0xFF22222C)
private val Accent = Color(0xFFF2C6E0)
private val Dim = Color(0xB3FFFFFF)
private val Faint = Color(0x73FFFFFF)
private val profileColors = listOf(Color(0xFFE50914), Color(0xFF2F80ED), Color(0xFFF2C94C), Color(0xFF27AE60), Color(0xFFBB6BD9))

data class TvAppRef(val pkg: String, val label: String)
data class AgentCard(val title: String, val year: Int, val poster: String, val on: List<String>, val elsewhere: List<String>)
data class ChatMsg(val mine: Boolean, val text: String, val cards: List<AgentCard> = emptyList())
data class ShowRef(val title: String, val pkg: String, val label: String, val image: String, val meta: String)
data class TvState(
    val profiles: List<String> = emptyList(),
    val active: Int = 0,
    val night: Boolean = false,
    val lofi: Boolean = true,
    val lofiLevel: Int = 2,
    val sleepAt: Long = 0L,
    val nowLabel: String = "",
    val onHome: Boolean = false,
    val shows: List<ShowRef> = emptyList(),
    val home: List<TvAppRef> = emptyList()
)

private enum class Tab(val label: String, val icon: ImageVector) {
    Remote("Remote", Icons.Rounded.SettingsRemote),
    Ask("Ask", Icons.Rounded.AutoAwesome),
    Tv("TV", Icons.Rounded.Tv),
    Search("Search", Icons.Rounded.Search),
    Controls("Controls", Icons.Rounded.Tune)
}

class MainActivity : ComponentActivity() {

    private lateinit var hub: HubClient
    private lateinit var link: ControlLink
    private var discovery: Discovery? = null
    private val scope = MainScope()

    private var paired by mutableStateOf(false)
    private var foundHost by mutableStateOf<String?>(null)
    private var tv by mutableStateOf(TvState())
    private var reachable by mutableStateOf(true)
    private var heard by mutableStateOf("")
    private var lastMessage by mutableStateOf("")
    private var voiceTarget: ((String) -> Unit)? = null
    private val chat = mutableStateListOf<ChatMsg>()
    private var thinking by mutableStateOf(false)
    private var handsFree by mutableStateOf(true)
    private var tts: TextToSpeech? = null
    private var voiceOn by mutableStateOf(true)
    private var ttsReady = false
    private val session by lazy {
        val p = getSharedPreferences("remote", MODE_PRIVATE)
        p.getString("session", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("session", it).apply() }
    }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) voiceTarget?.invoke(said)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = HubClient(this)
        link = ControlLink(hub)
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("en", "ZA")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    @Deprecated("old api") override fun onError(id: String?) {}
                    override fun onDone(id: String?) {
                        // the agent asked something ("want me to play it?"): listen for the answer straight away
                        if (handsFree && id == "q") runOnUiThread { askByVoice() }
                    }
                })
            }
        }
        paired = !hub.token.isNullOrBlank() && !hub.host.isNullOrBlank()
        foundHost = hub.host
        discovery = Discovery(this) { h ->
            runOnUiThread {
                if (!paired) foundHost = h
                else if (hub.host != h) { hub.host = h; link.poke() }   // TV got a new IP: follow it
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
                    if (!paired) PairScreen() else Main()
                    ListeningSheet()
                }
            }
        }
    }

    override fun onStart() { super.onStart(); discovery?.start(); link.start() }
    override fun onStop() { discovery?.stop(); link.stop(); super.onStop() }

    /** Phone volume buttons change the TV volume while Mi Remote is open. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (paired && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            link.key(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "volup" else "voldown")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- patient listening ----------------
    private var listening by mutableStateOf(false)
    private var liveWords by mutableStateOf("")
    private var listenPrompt by mutableStateOf("")
    private var recognizer: android.speech.SpeechRecognizer? = null
    private var pendingListen: Pair<String, (String) -> Unit>? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingListen; pendingListen = null
        if (p != null) { if (ok) listen(p.first, p.second) else listenWithGoogle(p.first, p.second) }
    }

    /**
     * Waits up to 8 s for you to start talking, shows the words as you say them and only stops
     * 2.5 s after you finish. One silent retry if it heard nothing at all.
     */
    private fun listen(prompt: String, then: (String) -> Unit) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { listenWithGoogle(prompt, then); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingListen = prompt to then; micPermission.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        tts?.stop()
        voiceTarget = then
        listenPrompt = prompt; liveWords = ""; listening = true
        startRecognizer(retry = true)
    }

    private fun startRecognizer(retry: Boolean) {
        recognizer?.destroy()
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        var heardAnything = false
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { heardAnything = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) liveWords = it }
            }
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.ifBlank { null } ?: liveWords.ifBlank { null }
                listening = false
                if (said != null) voiceTarget?.invoke(said)
            }
            override fun onError(error: Int) {
                val nothing = error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == android.speech.SpeechRecognizer.ERROR_NO_MATCH
                if (nothing && retry && !heardAnything && liveWords.isBlank()) { startRecognizer(retry = false); return }
                listening = false
                if (liveWords.isNotBlank()) voiceTarget?.invoke(liveWords)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-ZA")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        r.startListening(i)
    }

    private fun stopListening(send: Boolean) {
        val words = liveWords
        recognizer?.cancel(); listening = false
        if (send && words.isNotBlank()) voiceTarget?.invoke(words)
    }

    /** Phones without an on-device recogniser: Google's own voice screen. */
    private fun listenWithGoogle(prompt: String, then: (String) -> Unit) {
        voiceTarget = then
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-ZA")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try { speech.launch(i) } catch (e: Exception) { lastMessage = "Voice typing isn't available on this phone" }
    }

    @Composable
    private fun ListeningSheet() {
        if (!listening) return
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { stopListening(send = true) }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF3A2456), Color(0xFF5B2F4E)))).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color(0xFF3A2456), modifier = Modifier.size(32.dp))
                }
                Text(if (liveWords.isBlank()) "I'm listening, take your time" else liveWords, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(if (liveWords.isBlank()) listenPrompt else "Tap anywhere when you're done", color = Color(0xB3FFFFFF), fontSize = 13.sp)
            }
        }
    }

    private fun talkToTv() = listen("Try: what's the latest Superman movie?") { said ->
        heard = said; lastMessage = "Working on it..."
        askAgent(said) { reply -> lastMessage = reply }
    }

    private fun askByVoice() = listen("Ask Mi TV anything") { askAgent(it) }

    /** Send something to the Mi TV agent, show and speak the answer. */
    private fun askAgent(text: String, onReply: ((String) -> Unit)? = null) {
        chat.add(ChatMsg(true, text))
        thinking = true
        scope.launch {
            val r = hub.agent(session, text)
            thinking = false
            val reply = r?.optString("reply")?.ifBlank { null } ?: "I can't reach Mi TV right now."
            val arr = r?.optJSONArray("cards") ?: JSONArray()
            val cards = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                AgentCard(o.optString("title"), o.optInt("year"), o.optString("poster"), list("on"), list("elsewhere"))
            }
            chat.add(ChatMsg(false, reply, cards))
            onReply?.invoke(reply)
            if (r?.optBoolean("speak", true) != false) voiceOn = true else voiceOn = false
            if (ttsReady && voiceOn) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, if (reply.trim().endsWith("?")) "q" else "a")
            refreshState()
        }
    }

    override fun onDestroy() { tts?.shutdown(); recognizer?.destroy(); super.onDestroy() }

    private suspend fun refreshState() {
        val r = hub.state()
        reachable = r != null
        if (r == null || r.optInt("_code") != 200) return
        val profiles = r.optJSONArray("profiles") ?: JSONArray()
        val shows = r.optJSONArray("shows") ?: JSONArray()
        val home = r.optJSONArray("home") ?: JSONArray()
        tv = TvState(
            profiles = (0 until profiles.length()).map { profiles.getString(it) },
            active = r.optInt("active"),
            night = r.optBoolean("night"),
            lofi = r.optBoolean("lofi", true),
            lofiLevel = r.optInt("lofiLevel", 2),
            sleepAt = r.optLong("sleepAt"),
            nowLabel = r.optJSONObject("now")?.optString("label").orEmpty(),
            onHome = r.optBoolean("onHome"),
            shows = (0 until shows.length()).map { i ->
                val o = shows.getJSONObject(i)
                ShowRef(o.optString("title"), o.optString("pkg"), o.optString("label"),
                    o.optString("backdrop").ifBlank { o.optString("poster") }, o.optString("meta"))
            },
            home = (0 until home.length()).map { TvAppRef(home.getJSONObject(it).getString("pkg"), home.getJSONObject(it).getString("label")) }
        )
    }

    // ================================================================ pairing

    @Composable
    private fun PairScreen() {
        var ip by remember { mutableStateOf(foundHost ?: "") }
        var code by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        LaunchedEffect(foundHost) { if (ip.isBlank() && foundHost != null) ip = foundHost!! }

        Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(30.dp))
            Text("Mi Remote", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("Connect to Mi TV once. It stays connected until you uninstall the app.", color = Dim, fontSize = 15.sp)
            Text("On the TV: gear icon, then Mi Remote. Type the 4 digit code here.", color = Faint, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (foundHost != null) "Found Mi TV on your Wi-Fi" else "Looking for Mi TV on your Wi-Fi...", color = if (foundHost != null) Accent else Faint, fontSize = 13.sp)
            Field(ip, "TV address", KeyboardType.Uri) { ip = it.trim() }
            Field(code, "Pairing code", KeyboardType.NumberPassword) { if (it.length <= 4) code = it.filter(Char::isDigit) }
            BigButton("Connect", enabled = ip.isNotBlank() && code.length == 4) {
                status = "Connecting..."
                scope.launch {
                    hub.host = ip
                    status = if (hub.pair(code)) { paired = true; link.poke(); "" }
                    else if (hub.hello() == null) "Can't reach the TV at $ip. Is the phone on the same Wi-Fi?" else "Wrong code"
                }
            }
            if (status.isNotEmpty()) Text(status, color = Dim, fontSize = 14.sp)
        }
    }

    // ================================================================ main

    @Composable
    private fun Main() {
        var tab by remember { mutableStateOf(Tab.Remote) }
        val connected by link.connected.collectAsState()
        val rejected by link.rejected.collectAsState()

        LaunchedEffect(tab) {
            while (true) { refreshState(); delay(if (tab == Tab.Tv || tab == Tab.Controls) 3000 else 8000) }
        }

        Column(Modifier.fillMaxSize()) {
            // header
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mi Remote", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Color(0xFF5BD68A) else Color(0xFFF2C94C)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                connected && tv.nowLabel.isNotBlank() && !tv.onHome -> "Mi TV  ·  ${tv.nowLabel}"
                                connected -> "Connected to Mi TV"
                                else -> "Reconnecting..."
                            },
                            color = Faint, fontSize = 12.sp
                        )
                    }
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3A1A1A)).clickable { link.key("power") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Power", tint = Color(0xFFFF8A8A), modifier = Modifier.size(22.dp))
                }
            }

            if (rejected) RepairBanner()

            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Remote -> RemoteTab()
                    Tab.Ask -> AskTab()
                    Tab.Tv -> TvTab()
                    Tab.Search -> SearchTab()
                    Tab.Controls -> ControlsTab()
                }
            }

            NavigationBar(containerColor = Color(0xFF0E0E14)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF1A1020), selectedTextColor = Accent, indicatorColor = Accent,
                            unselectedIconColor = Faint, unselectedTextColor = Faint
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun RepairBanner() {
        var code by remember { mutableStateOf("") }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF3A2A12)).padding(14.dp)) {
            Text("Mi TV was reset and doesn't know this phone any more. Enter the code on the TV (gear, Mi Remote) to reconnect.", color = Color.White, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(code, "Code", KeyboardType.NumberPassword) { if (it.length <= 4) code = it.filter(Char::isDigit) } }
                TextButton(onClick = { scope.launch { if (hub.pair(code)) link.poke() } }) { Text("Reconnect", color = Accent) }
            }
        }
    }

    // ================================================================ remote tab

    @Composable
    private fun RemoteTab() {
        val view = LocalView.current
        var touchpad by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }
        fun tap() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        fun key(k: String) { tap(); link.key(k) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            VoiceCard()

            // D-pad or swipe pad
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(4.dp)) {
                Segment("Buttons", !touchpad) { touchpad = false }
                Segment("Swipe pad", touchpad) { touchpad = true }
            }
            if (touchpad) SwipePad(onKey = { key(it) }) else DPad(onKey = { key(it) }, onLongOk = { tap(); link.longKey("ok") })

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { key("back") }
                RoundButton(Icons.Rounded.Home, "Home") { key("home") }
                RoundButton(Icons.Rounded.PlayArrow, "Play/Pause") { key("playpause") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundButton(Icons.Rounded.FastRewind, "Rewind") { key("rewind") }
                RoundButton(Icons.AutoMirrored.Rounded.VolumeDown, "Vol -") { key("voldown") }
                RoundButton(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { key("mute") }
                RoundButton(Icons.AutoMirrored.Rounded.VolumeUp, "Vol +") { key("volup") }
                RoundButton(Icons.Rounded.FastForward, "Forward") { key("forward") }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Field(typed, "Type on the TV", KeyboardType.Text, ImeAction.Send, onSend = { if (typed.isNotBlank()) { link.type(typed); typed = "" } }) { typed = it }
                }
                Spacer(Modifier.width(10.dp))
                SmallIcon(Icons.AutoMirrored.Rounded.Send) { if (typed.isNotBlank()) { link.type(typed); typed = "" } }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    @Composable
    private fun VoiceCard() {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF3A2456), Color(0xFF5B2F4E))))
                .clickable { talkToTv() }.padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Mic, contentDescription = "Talk", tint = Color(0xFF3A2456), modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (heard.isBlank()) "Tap and talk to Mi TV" else "“$heard”", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(lastMessage.ifBlank { "Play Inception on Netflix  ·  Open YouTube Kids" }, color = Dim, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }

    @Composable
    private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) CardHi else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp)
        ) { Text(label, color = if (selected) Color.White else Faint, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun DPad(onKey: (String) -> Unit, onLongOk: () -> Unit) {
        Box(Modifier.size(250.dp).clip(CircleShape).background(Card).border(1.dp, Color(0x22FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            ArrowHit(Icons.Rounded.KeyboardArrowUp, Modifier.align(Alignment.TopCenter)) { onKey("up") }
            ArrowHit(Icons.Rounded.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter)) { onKey("down") }
            ArrowHit(Icons.Rounded.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart)) { onKey("left") }
            ArrowHit(Icons.Rounded.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd)) { onKey("right") }
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(CardHi).combinedClickable(onClick = { onKey("ok") }, onLongClick = onLongOk),
                contentAlignment = Alignment.Center
            ) { Text("OK", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    /** Apple TV style: swipe to move, tap to select, hold to long press. */
    @Composable
    private fun SwipePad(onKey: (String) -> Unit) {
        var accX by remember { mutableStateOf(0f) }
        var accY by remember { mutableStateOf(0f) }
        val step = 90f
        Box(
            Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1B1B25), Color(0xFF111118))))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(28.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onKey("ok") }, onLongPress = { link.longKey("ok") })
                }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { accX = 0f; accY = 0f }) { change, drag ->
                        change.consume()
                        accX += drag.x; accY += drag.y
                        while (abs(accX) >= step || abs(accY) >= step) {
                            if (abs(accX) >= abs(accY)) { onKey(if (accX > 0) "right" else "left"); accX -= step * if (accX > 0) 1 else -1 }
                            else { onKey(if (accY > 0) "down" else "up"); accY -= step * if (accY > 0) 1 else -1 }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Swipe to move  ·  tap to select\nhold to long press", color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }

    // ================================================================ Ask tab

    @Composable
    private fun AskTab() {
        var typed by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        LaunchedEffect(chat.size, thinking) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1 + if (thinking) 1 else 0) }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (chat.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Accent, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Ask Mi TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        listOf("What's the latest Superman movie?", "Is The Night Agent on Netflix?", "Play Bluey on YouTube Kids", "Put on some Coldplay", "Sleep in 30 minutes")
                            .forEach { ex -> Text("\u201C$ex\u201D", color = Faint, fontSize = 14.sp, modifier = Modifier.padding(3.dp).clickable { askAgent(ex) }) }
                    }
                }
                items(chat.size) { i -> Bubble(chat[i]) }
                if (thinking) item { Text("Mi TV is thinking...", color = Faint, fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Field(typed, "Ask anything", KeyboardType.Text, ImeAction.Send, onSend = { if (typed.isNotBlank()) { askAgent(typed); typed = "" } }) { typed = it }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7B4FD0), Color(0xFFE0609B)))).clickable { askByVoice() },
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, contentDescription = "Talk", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
        }
    }

    @Composable
    private fun Bubble(m: ChatMsg) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(if (m.mine) Accent else Card).padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text(m.text, color = if (m.mine) Color(0xFF1A1020) else Color.White, fontSize = 15.sp) }
            if (m.cards.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(m.cards.size) { i ->
                        val c = m.cards[i]
                        Column(Modifier.width(130.dp).clip(RoundedCornerShape(12.dp)).background(Card)
                            .clickable { askAgent("Play ${c.title}${if (c.year > 0) " (${c.year})" else ""}") }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(CardHi)) {
                                if (c.poster.isNotBlank()) AsyncImage(model = c.poster, contentDescription = c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.padding(8.dp)) {
                                Text(c.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (c.year > 0) c.year.toString() else "", color = Faint, fontSize = 11.sp)
                                Text(
                                    when { c.on.isNotEmpty() -> "On ${c.on.first()}"; c.elsewhere.isNotEmpty() -> "Only on ${c.elsewhere.first()}"; else -> "Not streaming" },
                                    color = if (c.on.isNotEmpty()) Color(0xFF5BD68A) else Faint, fontSize = 11.sp, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ================================================================ TV tab

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TvTab() {
        val ctx = LocalContext.current
        var confirmRemove by remember { mutableIntStateOf(-1) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (!reachable) Text("Can't reach Mi TV right now. Check the TV is on and on the same Wi-Fi.", color = Color(0xFFF2C94C), fontSize = 13.sp)

            // who's watching
            SectionLabel("WHO'S WATCHING")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                tv.profiles.forEachIndexed { i, name ->
                    val sel = i == tv.active
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { scope.launch { hub.profile(i); refreshState() } }) {
                        Box(
                            Modifier.size(if (sel) 62.dp else 54.dp).clip(RoundedCornerShape(16.dp))
                                .background(profileColors[i % profileColors.size].copy(alpha = if (sel) 1f else 0.5f))
                                .border(if (sel) 2.dp else 0.dp, Color.White, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text(name.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        Text(name, color = if (sel) Color.White else Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            SectionLabel("CONTINUE WATCHING")
            if (tv.shows.isEmpty()) Text("Nothing yet. Use Search to find a show or movie and add it.", color = Faint, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(tv.shows) { i, s ->
                    Column(
                        Modifier.width(220.dp).clip(RoundedCornerShape(14.dp)).background(Card)
                            .combinedClickable(onClick = { scope.launch { lastMessage = hub.playShow(tv.active, i)?.optString("message").orEmpty() } }, onLongClick = { confirmRemove = i })
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(CardHi)) {
                            if (s.image.isNotBlank()) AsyncImage(model = s.image, contentDescription = s.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White)
                            }
                        }
                        Column(Modifier.padding(10.dp)) {
                            Text(s.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.label.ifBlank { "Any app" }, color = Faint, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (tv.shows.isNotEmpty()) Text("Tap to play on the TV  ·  hold to remove", color = Faint, fontSize = 11.sp)

            SectionLabel("APPS")
            AppGrid(tv.home, ctx) { a -> scope.launch { hub.open(a.pkg) } }
            Spacer(Modifier.height(16.dp))
        }

        if (confirmRemove >= 0) {
            val s = tv.shows.getOrNull(confirmRemove)
            AlertDialog(
                onDismissRequest = { confirmRemove = -1 },
                title = { Text("Remove ${s?.title ?: ""}?") },
                text = { Text("It comes off Continue watching for ${tv.profiles.getOrNull(tv.active) ?: "this profile"}.") },
                confirmButton = { TextButton(onClick = { val i = confirmRemove; confirmRemove = -1; scope.launch { hub.removeShow(tv.active, i); refreshState() } }) { Text("Remove") } },
                dismissButton = { TextButton(onClick = { confirmRemove = -1 }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    private fun AppGrid(apps: List<TvAppRef>, ctx: android.content.Context, onClick: (TvAppRef) -> Unit) {
        // simple wrapping grid (inside a scrolling column, so no lazy grid)
        apps.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { a ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Card).clickable { onClick(a) }) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(hub.iconUrl(a.pkg)).addHeader("x-hub-token", hub.token ?: "").crossfade(true).build(),
                            contentDescription = a.label, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(CardHi)
                        )
                        Text(a.label, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // ================================================================ search tab

    @Composable
    private fun SearchTab() {
        var q by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<TitleSearch.Result>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf<TitleSearch.Result?>(null) }
        val localScope = rememberCoroutineScope()
        fun run(text: String) { q = text; if (text.isBlank()) return; busy = true; localScope.launch { results = TitleSearch.search(text); busy = false } }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(q, "Search any show or movie", KeyboardType.Text, ImeAction.Search, onSend = { run(q) }) { q = it } }
                Spacer(Modifier.width(8.dp))
                SmallIcon(Icons.Rounded.Mic) { listen("Say a show or movie") { run(it) } }
            }
            Spacer(Modifier.height(10.dp))
            if (busy) Text("Searching...", color = Faint, fontSize = 13.sp)
            if (!busy && results.isEmpty() && q.isNotBlank()) Text("Nothing found", color = Faint, fontSize = 13.sp)
            LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(results) { r ->
                    Column(Modifier.clickable { picked = r }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(CardHi)) {
                            if (r.poster.isNotBlank()) AsyncImage(model = r.poster, contentDescription = r.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text(r.kind.uppercase(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                        Text(r.title, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(r.year, color = Faint, fontSize = 11.sp)
                    }
                }
            }
        }
        picked?.let { TitleSheet(it) { picked = null } }
    }

    @Composable
    private fun TitleSheet(r: TitleSearch.Result, onDismiss: () -> Unit) {
        var allApps by remember { mutableStateOf<List<TvAppRef>>(emptyList()) }
        var chosen by remember { mutableStateOf<TvAppRef?>(null) }
        var status by remember { mutableStateOf("") }
        LaunchedEffect(r) {
            val a = hub.apps()?.optJSONArray("all") ?: JSONArray()
            allApps = (0 until a.length()).map { TvAppRef(a.getJSONObject(it).getString("pkg"), a.getJSONObject(it).getString("label")) }
            // guess the app from the network name (Netflix, Disney+, Prime Video...)
            chosen = allApps.firstOrNull { r.network.isNotBlank() && (it.label.contains(r.network, true) || r.network.contains(it.label, true)) }
                ?: tv.home.firstOrNull()?.let { h -> allApps.firstOrNull { it.pkg == h.pkg } }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(r.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(listOf(r.kind, r.year, r.network, r.genres).filter { it.isNotBlank() }.joinToString("  ·  "), color = Faint, fontSize = 12.sp)
                    if (r.summary.isNotBlank()) Text(r.summary, fontSize = 13.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Text("Watch it on", color = Faint, fontSize = 12.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        allApps.forEach { a ->
                            val sel = chosen?.pkg == a.pkg
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) Accent else CardHi).clickable { chosen = a }.padding(10.dp)) {
                                Text(a.label, color = if (sel) Color(0xFF1A1020) else Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                    if (status.isNotBlank()) Text(status, color = Accent, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = chosen != null, onClick = {
                    val c = chosen ?: return@TextButton
                    status = "Starting on the TV..."
                    scope.launch { hub.addShow(r.toShowJson(c.pkg, c.label), play = true); refreshState(); onDismiss() }
                }) { Text("Play on TV") }
            },
            dismissButton = {
                TextButton(enabled = chosen != null, onClick = {
                    val c = chosen ?: return@TextButton
                    status = "Adding..."
                    scope.launch { hub.addShow(r.toShowJson(c.pkg, c.label), play = false); refreshState(); onDismiss() }
                }) { Text("Add to Continue watching") }
            }
        )
    }

    // ================================================================ controls tab

    @Composable
    private fun ControlsTab() {
        var confirmUnpair by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("SLEEP TIMER")
            Text(
                if (tv.sleepAt > 0) "Sleep timer: ${((tv.sleepAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(0)} min left" else "Sleep timer",
                color = Dim, fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { m -> Chip("$m") { scope.launch { hub.sleep(m); refreshState() } } }
                Chip("Off") { scope.launch { hub.sleep(0); refreshState() } }
            }

            SectionLabel("SCREENSAVER")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Start art + lofi") { scope.launch { hub.screensaver() } }
                Chip("Next song + picture") { scope.launch { hub.skipArt() } }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lofi radio", color = Color.White, fontSize = 15.sp)
                    Text("Plays with the art screensaver", color = Faint, fontSize = 12.sp)
                }
                Switch(checked = tv.lofi, onCheckedChange = { on -> scope.launch { hub.lofi(on); refreshState() } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF1A1020)))
            }
            Text("Lofi volume (always softer than the TV)", color = Dim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Whisper", 2 to "Soft", 3 to "Medium").forEach { (lv, name) ->
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (tv.lofiLevel == lv) Accent else Card)
                        .clickable { scope.launch { hub.lofiLevel(lv); refreshState() } }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(name, color = if (tv.lofiLevel == lv) Color(0xFF1A1020) else Color.White, fontSize = 13.sp)
                    }
                }
            }
            if (note.isNotBlank()) Text(note, color = Faint, fontSize = 12.sp)

            SectionLabel("AI AGENT")
            AgentSettings()

            SectionLabel("CONNECTION")
            var diag by remember { mutableStateOf("Checking the TV...") }
            LaunchedEffect(Unit) {
                val d = hub.diag()
                diag = when {
                    d == null -> "Can't reach Mi TV"
                    d.optBoolean("injector") -> "Buttons: instant mode is on"
                    d.optBoolean("adb") -> "Buttons: slow mode (instant mode is starting: ${d.optString("injectorStart")})"
                    else -> "Buttons: basic mode. On the TV press Allow on the \"Allow USB debugging\" box (tick Always allow)"
                }
            }
            Text(diag, color = Dim, fontSize = 13.sp)
            Text("TV address: ${hub.host ?: "unknown"}", color = Faint, fontSize = 12.sp)
            Text("Mi Remote stays paired until you uninstall it. If the TV gets a new address it is found again automatically.", color = Faint, fontSize = 12.sp)
            TextButton(onClick = { confirmUnpair = true }) { Text("Unpair this phone", color = Color(0xFFFF8A8A)) }
            Spacer(Modifier.height(16.dp))
        }
        if (confirmUnpair) AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("You'll need the code on the TV to connect again.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; hub.token = null; paired = false }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } }
        )
    }

    @Composable
    private fun AgentSettings() {
        var hasKey by remember { mutableStateOf(false) }
        var subs by remember { mutableStateOf(listOf("Netflix", "DStv")) }
        var key by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            hub.agentStatus()?.let { st ->
                hasKey = st.optBoolean("hasKey"); voiceOn = st.optBoolean("voice", true)
                st.optJSONArray("subscriptions")?.let { a -> subs = (0 until a.length()).map { a.getString(it) } }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (hasKey) "Gemini is connected. Ask anything in the Ask tab." else "Works now with the built-in brain. For full conversation add a free Gemini key.",
                color = Dim, fontSize = 13.sp)
            Text("Free key: aistudio.google.com/apikey  ·  Create API key  ·  copy it here", color = Faint, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(key, if (hasKey) "Replace key" else "Gemini API key", KeyboardType.Password) { key = it.trim() } }
                TextButton(enabled = key.length > 20, onClick = {
                    scope.launch { val r = hub.setAgentKey(key); hasKey = r?.optBoolean("hasKey") == true; key = ""; msg = if (hasKey) "Saved on the TV for everyone" else "Couldn't save" }
                }) { Text("Save", color = Accent) }
            }
            if (msg.isNotBlank()) Text(msg, color = Accent, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Spoken replies", color = Color.White, fontSize = 15.sp)
                    Text("The agent talks back out loud", color = Faint, fontSize = 12.sp)
                }
                Switch(checked = voiceOn, onCheckedChange = { on -> voiceOn = on; if (!on) tts?.stop(); scope.launch { hub.setVoice(on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF1A1020)))
            }
            Text("Apps you pay for (the agent plays from these)", color = Faint, fontSize = 12.sp)
            var tvApps by remember { mutableStateOf(listOf<String>()) }
            var custom by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                val a = hub.apps()?.optJSONArray("all") ?: JSONArray()
                val skip = Regex("youtube|settings|google|play store|files|downloader|mi tv|live channels|cast", RegexOption.IGNORE_CASE)
                tvApps = (0 until a.length()).map { a.getJSONObject(it).getString("label") }.filter { !skip.containsMatchIn(it) }
            }
            val options = (listOf("Netflix", "DStv", "Spotify", "Showmax", "Prime Video", "Disney+", "Apple TV") + tvApps + subs)
                .distinctBy { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { svc ->
                    val on = subs.any { it.equals(svc, true) }
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Accent else CardHi).clickable {
                        subs = if (on) subs.filterNot { it.equals(svc, true) } else subs + svc
                        scope.launch { hub.setSubscriptions(subs) }
                    }.padding(horizontal = 10.dp, vertical = 8.dp)) { Text(svc, color = if (on) Color(0xFF1A1020) else Color.White, fontSize = 12.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(custom, "Add another app you pay for", KeyboardType.Text) { custom = it } }
                TextButton(enabled = custom.isNotBlank(), onClick = {
                    val n = custom.trim(); custom = ""
                    if (subs.none { it.equals(n, true) }) { subs = subs + n; scope.launch { hub.setSubscriptions(subs) } }
                }) { Text("Add", color = Accent) }
            }
        }
    }

    // ================================================================ parts

    @Composable
    private fun SectionLabel(t: String) = Text(t, color = Faint, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)

    @Composable
    private fun Chip(label: String, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, color = Color.White, fontSize = 13.sp)
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
            Box(Modifier.size(60.dp).clip(CircleShape).background(Card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Text(label, color = Faint, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun SmallIcon(icon: ImageVector, onClick: () -> Unit) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }

    @Composable
    private fun Field(value: String, label: String, type: KeyboardType, ime: ImeAction = ImeAction.Done, onSend: (() -> Unit)? = null, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime),
            keyboardActions = KeyboardActions(onAny = { onSend?.invoke() }),
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
                .background(if (enabled) Accent else Color(0x33FFFFFF)).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(text, color = if (enabled) Color(0xFF1A1020) else Faint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Suppress("unused") private fun unused(o: JSONObject) = o
}
