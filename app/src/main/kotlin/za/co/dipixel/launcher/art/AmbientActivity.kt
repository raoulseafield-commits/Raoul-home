package za.co.dipixel.launcher.art

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import za.co.dipixel.launcher.data.ConfigStore

/** Start the art + lofi screensaver yourself (Art button on the dock, or Mi Remote). */
class AmbientActivity : Activity() {
    private var ambient: Ambient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val a = Ambient(this, ConfigStore.load(this).lofiRadio)
        ambient = a
        setContentView(a.build())
        a.start()
    }

    override fun onDestroy() { ambient?.stop(); super.onDestroy() }
    override fun onStop() { super.onStop(); finish() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_HOME)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && ambient?.onKey(event.keyCode) == true) return true
        if (event.action == KeyEvent.ACTION_DOWN && ambient?.handles(event.keyCode) == true) return true
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
