package za.co.dipixel.launcher.art

import android.service.dreams.DreamService
import android.view.KeyEvent
import za.co.dipixel.launcher.data.ConfigStore

/** "Mi TV Art" system screensaver: paintings + lofi radio. OK skips, Back leaves. */
class ArtDream : DreamService() {
    private var ambient: Ambient? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true          // so OK can skip instead of waking the TV
        isFullscreen = true
        isScreenBright = true
        val a = Ambient(this, ConfigStore.load(this).lofiRadio)
        ambient = a
        setContentView(a.build())
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); ambient?.start() }
    override fun onDreamingStopped() { ambient?.stop(); super.onDreamingStopped() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && ambient?.onKey(event.keyCode) == true) return true
        if (event.action == KeyEvent.ACTION_DOWN && (ambient?.handles(event.keyCode) == true)) return true
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
