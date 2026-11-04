package za.co.dipixel.launcher.art

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.audiofx.Visualizer
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Small line-drawn weather icon (sun, moon, cloud, rain, storm, snow, fog), white like the text. */
class WeatherIconView(context: Context) : View(context) {
    var code: Int = -1; set(v) { field = v; invalidate() }
    var isDay: Boolean = true; set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xF2FFFFFF.toInt()
        setShadowLayer(6f, 0f, 1f, 0x88000000.toInt())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xF2FFFFFF.toInt(); setShadowLayer(6f, 0f, 1f, 0x88000000.toInt()) }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // shadow layer needs software on paths

    override fun onDraw(c: Canvas) {
        if (code < 0) return
        val s = min(width, height).toFloat()
        stroke.strokeWidth = s * 0.07f
        val kind = when (code) {
            0, 1 -> if (isDay) "sun" else "moon"
            2 -> if (isDay) "partly" else "partlynight"
            3 -> "cloud"
            45, 48 -> "fog"
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
            71, 73, 75, 77, 85, 86 -> "snow"
            95, 96, 99 -> "storm"
            else -> "cloud"
        }
        when (kind) {
            "sun" -> sun(c, s * 0.5f, s * 0.5f, s * 0.2f, s)
            "moon" -> moon(c, s * 0.5f, s * 0.5f, s * 0.3f)
            "partly" -> { sun(c, s * 0.62f, s * 0.36f, s * 0.14f, s * 0.7f); cloud(c, s, dy = s * 0.08f) }
            "partlynight" -> { moon(c, s * 0.64f, s * 0.34f, s * 0.18f); cloud(c, s, dy = s * 0.08f) }
            "cloud" -> cloud(c, s)
            "fog" -> for (i in 0..2) { val y = s * (0.38f + i * 0.16f); c.drawLine(s * (0.14f + i * 0.05f), y, s * (0.86f - i * 0.04f), y, stroke) }
            "rain" -> { cloud(c, s, dy = -s * 0.1f); for (i in 0..2) { val x = s * (0.34f + i * 0.16f); c.drawLine(x, s * 0.68f, x - s * 0.06f, s * 0.86f, stroke) } }
            "snow" -> { cloud(c, s, dy = -s * 0.1f); for (i in 0..2) c.drawCircle(s * (0.34f + i * 0.16f), s * (0.76f + (i % 2) * 0.08f), s * 0.035f, fill) }
            "storm" -> {
                cloud(c, s, dy = -s * 0.1f)
                val p = Path().apply { moveTo(s * 0.54f, s * 0.6f); lineTo(s * 0.44f, s * 0.76f); lineTo(s * 0.54f, s * 0.76f); lineTo(s * 0.46f, s * 0.92f) }
                c.drawPath(p, stroke)
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        c.drawCircle(cx, cy, r, stroke)
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val r1 = r * 1.5f; val r2 = r * 2.05f
            c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), stroke)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        val outer = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW) }
        outer.op(cut, Path.Op.DIFFERENCE)
        c.drawPath(outer, fill)
    }

    private fun cloud(c: Canvas, s: Float, dy: Float = 0f) {
        val p = Path().apply { addRoundRect(RectF(s * 0.14f, s * 0.5f + dy, s * 0.86f, s * 0.74f + dy), s * 0.12f, s * 0.12f, Path.Direction.CW) }
        p.op(Path().apply { addCircle(s * 0.38f, s * 0.5f + dy, s * 0.14f, Path.Direction.CW) }, Path.Op.UNION)
        p.op(Path().apply { addCircle(s * 0.58f, s * 0.45f + dy, s * 0.19f, Path.Direction.CW) }, Path.Op.UNION)
        c.drawPath(p, Paint(fill).apply { color = 0x33FFFFFF })
        c.drawPath(p, stroke)
    }
}

/**
 * Four small bars that move with the music, like the lofi radio badge in the video.
 * Uses the system Visualizer on the radio's audio session; if that isn't allowed
 * it falls back to a gentle "breathing" motion so it still looks alive.
 */
@SuppressLint("ViewConstructor")
class BeatBars(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB347.toInt() }
    private val level = FloatArray(4) { 0.25f }
    private val target = FloatArray(4) { 0.25f }
    private val peak = FloatArray(4) { 0.05f }
    private var visualizer: Visualizer? = null
    private var real = false
    private var playing = false
    private var t = 0f
    private val rect = RectF()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!isAttachedToWindow) return
            t += 0.016f
            for (i in 0 until 4) {
                val goal = when {
                    !playing -> 0.15f
                    real -> target[i]
                    else -> 0.35f + 0.3f * (0.5f + 0.5f * sin(t * (5.1f + i * 1.7f) + i * 1.3f)) * (0.6f + 0.4f * sin(t * 1.9f + i).toFloat())
                }
                // quick rise, slower fall: reads as a beat
                level[i] += (goal - level[i]) * (if (goal > level[i]) 0.55f else 0.16f)
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun attach(sessionId: Int) {
        release()
        try {
            val v = Visualizer(sessionId)
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, samplingRate: Int) { if (fft != null) onFft(fft) }
            }, min(Visualizer.getMaxCaptureRate(), 20000), false, true)
            v.enabled = true
            visualizer = v; real = true
        } catch (e: Throwable) { real = false }
    }

    fun setPlaying(on: Boolean) { playing = on }

    private fun onFft(fft: ByteArray) {
        val bins = fft.size / 2
        // bass, low mids, mids, highs
        val bands = arrayOf(1..2, 3..6, 7..14, 15..min(40, bins - 1))
        for (b in 0 until 4) {
            var sum = 0f
            for (k in bands[b]) { val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat(); sum = max(sum, hypot(re, im)) }
            peak[b] = max(peak[b] * 0.995f, sum)                    // slowly adapting ceiling per band
            target[b] = (0.12f + 0.88f * (sum / max(peak[b], 1f))).coerceIn(0.12f, 1f)
        }
    }

    fun release() { try { visualizer?.enabled = false; visualizer?.release() } catch (_: Throwable) {}; visualizer = null; real = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Choreographer.getInstance().postFrameCallback(frame) }
    override fun onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(frame); release(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val gap = w * 0.12f
        val bw = (w - gap * 3) / 4
        for (i in 0 until 4) {
            val bh = max(bw, h * level[i])
            val x = i * (bw + gap)
            rect.set(x, h - bh, x + bw, h)
            c.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
    }
}
