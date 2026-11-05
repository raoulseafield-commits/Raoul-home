package za.co.dipixel.launcher.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import za.co.dipixel.launcher.hub.Agent
import za.co.dipixel.launcher.ui.RingColors
import java.util.Locale

/**
 * What the remote's Assistant button opens. Google does the listening (its remote microphone
 * handling is the best there is), the Mi TV agent decides what to do: Spotify first for songs,
 * the right app for films and series, straight into playback.
 * The answer shows on a glass card at the bottom of the TV (and is spoken when Spoken replies is on).
 */
class AssistActivity : ComponentActivity() {
    private enum class Stage { Listening, Thinking, Answer }
    private var stage by mutableStateOf(Stage.Listening)
    private var heard by mutableStateOf("")
    private var reply by mutableStateOf("")
    private val posters = mutableStateListOf<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var played = false

    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode != Activity.RESULT_OK || said.isNullOrBlank()) { finish(); return@registerForActivityResult }
        ask(said)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("en", "ZA")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) {}
                    override fun onDone(id: String?) { if (id == "q") runOnUiThread { startListening() } }
                })
            }
        }
        setContent { Overlay() }
        val said = intent.getStringExtra(EXTRA_SAID)
        if (said.isNullOrBlank()) startListening() else ask(said)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val said = intent.getStringExtra(EXTRA_SAID)
        if (said.isNullOrBlank()) startListening() else ask(said)
    }

    companion object { const val EXTRA_SAID = "said" }

    private fun startListening() {
        stage = Stage.Listening
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-ZA")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Mi TV")
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
        try { listen.launch(i) } catch (e: Exception) { reply = "Voice isn't available on this box"; stage = Stage.Answer; closeSoon(4000) }
    }

    private fun ask(text: String) {
        heard = text; stage = Stage.Thinking; posters.clear()
        Thread {
            val r = try { Agent.ask(applicationContext, "tv-remote", text) } catch (e: Exception) { null }
            runOnUiThread {
                reply = r?.text ?: "Sorry, something went wrong."
                r?.cards?.let { a -> for (i in 0 until minOf(3, a.length())) a.optJSONObject(i)?.let { posters += it.optString("poster") to it.optString("title") } }
                stage = Stage.Answer
                val question = reply.trim().endsWith("?")
                if (ttsReady && Agent.voice(this)) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, if (question) "q" else "a")
                else if (question) window.decorView.postDelayed({ startListening() }, 1800)
                if (!question) closeSoon(7000)
            }
        }.start()
    }

    private fun closeSoon(ms: Long) = window.decorView.postDelayed({ if (stage == Stage.Answer && !isFinishing) finish() }, ms)

    override fun onStop() {
        super.onStop()
        // the app we started is in front now: nothing left to show
        if (stage == Stage.Answer && !reply.trim().endsWith("?")) finish()
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    @Composable
    private fun Overlay() {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color(0x00000000), 1f to Color(0xCC000000))), contentAlignment = Alignment.BottomCenter) {
            val t = rememberInfiniteTransition(label = "a")
            val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "ang")
            val rad = Math.toRadians(angle.toDouble())
            val edge = Brush.linearGradient(RingColors, start = Offset(0f, 0f), end = Offset((600 * kotlin.math.cos(rad)).toFloat() + 600f, (600 * kotlin.math.sin(rad)).toFloat() + 300f))
            Column(
                Modifier.padding(bottom = 40.dp).width(720.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xE6121218))
                    .border(1.dp, edge, RoundedCornerShape(26.dp)).padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(RingColors)))
                    Spacer(Modifier.width(10.dp))
                    Text(when (stage) { Stage.Listening -> "Listening..."; Stage.Thinking -> "Finding it..."; Stage.Answer -> "Mi TV" },
                        color = Color(0xFFF2C6E0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                }
                if (heard.isNotBlank()) Text("“$heard”", color = Color(0xB3FFFFFF), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (stage == Stage.Answer) Text(reply, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp)
                if (stage == Stage.Answer && posters.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    posters.forEach { (img, title) ->
                        Column(Modifier.width(96.dp)) {
                            Box(Modifier.width(96.dp).height(140.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF22222A))) {
                                if (img.isNotBlank()) AsyncImage(model = img, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text(title, color = Color(0xB3FFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
