package za.co.dipixel.launcher.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import za.co.dipixel.launcher.data.Wallpapers
import java.io.InputStream
import kotlin.random.Random

/** "Mi TV Art" screensaver: museum paintings, slow pan and zoom, small clock, title and artist. */
class ArtDream : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var views: Array<ImageView>
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private var front = 0
    private var queue: MutableList<Art> = mutableListOf()
    private var fallbackIndex = 0
    private val slideMs = 40_000L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        views = Array(2) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
                root.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        // soft dark band at the bottom so the caption always reads
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), Gravity.BOTTOM))

        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), 0, dp(56), dp(40))
        }
        title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; setShadowLayer(8f, 0f, 2f, Color.BLACK) }
        artist = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 15f; setShadowLayer(8f, 0f, 2f, Color.BLACK) }
        caption.addView(title); caption.addView(artist)
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            setTextColor(Color.WHITE); textSize = 34f
            setShadowLayer(10f, 0f, 2f, Color.BLACK)
            setPadding(0, 0, dp(56), dp(40))
        }
        root.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))

        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        ArtRepo.refreshIfDue(this)
        next()
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    private val tick = Runnable { next() }

    private fun next() {
        if (queue.isEmpty()) queue = ArtRepo.all(this).shuffled().toMutableList()
        val art = queue.removeFirstOrNull()
        Thread {
            val bmp = art?.let { load(it) } ?: fallback()
            handler.post { show(bmp, art) }
        }.start()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, slideMs)
    }

    private fun show(bmp: Bitmap?, art: Art?) {
        if (bmp == null) return
        val incoming = views[1 - front]
        val outgoing = views[front]
        incoming.setImageBitmap(bmp)
        incoming.animate().cancel()
        // Ken Burns: start slightly zoomed and offset, drift the other way over the whole slide
        val dx = if (Random.nextBoolean()) 1f else -1f
        incoming.scaleX = 1.08f; incoming.scaleY = 1.08f
        incoming.translationX = 30f * dx; incoming.translationY = 0f
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(2500).withEndAction {
            incoming.animate().scaleX(1.16f).scaleY(1.16f).translationX(-30f * dx).setDuration(slideMs).start()
        }.start()
        outgoing.animate().alpha(0f).setDuration(2500).start()
        front = 1 - front
        title.text = art?.title ?: ""
        artist.text = art?.artist ?: ""
    }

    private fun load(art: Art): Bitmap? = try {
        if (art.file != null) decode { art.file.inputStream() } else decode { assets.open(art.asset!!) }
    } catch (e: Exception) { null }

    private fun fallback(): Bitmap? {
        val name = Wallpapers.ALL[fallbackIndex++ % Wallpapers.ALL.size]
        val id = resources.getIdentifier(name, "drawable", packageName)
        return if (id == 0) null else BitmapFactory.decodeResource(resources, id)
    }

    /** Decode at roughly screen size so the 2 GB box never runs out of memory. */
    private fun decode(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return open().use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
