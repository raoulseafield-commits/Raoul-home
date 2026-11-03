package za.co.dipixel.launcher.hub

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs as its own tiny process under the adb "shell" user (started with app_process,
 * the same trick scrcpy uses). The shell user is allowed to inject key presses, and
 * doing it here takes about 1 ms, where the `input keyevent` command takes ~500 ms.
 *
 * Listens on 127.0.0.1:8766 only. One command per line:
 *   k <keycode>      press and release
 *   l <keycode>      long press
 *   t <text>         type text
 *   p                ping, answers "p"
 */
object InjectorMain {
    const val PORT = 8766

    private lateinit var manager: Any
    private lateinit var inject: java.lang.reflect.Method

    @JvmStatic
    fun main(args: Array<String>) {
        manager = try {
            Class.forName("android.hardware.input.InputManagerGlobal").getDeclaredMethod("getInstance").invoke(null)!!
        } catch (e: Throwable) {
            Class.forName("android.hardware.input.InputManager").getDeclaredMethod("getInstance").invoke(null)!!
        }
        inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)

        val server = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        while (true) {
            val s = server.accept()
            Thread { serve(s) }.start()
        }
    }

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = s.getOutputStream()
            while (true) {
                val line = reader.readLine() ?: break
                try {
                    when {
                        line == "p" -> { out.write("p\n".toByteArray()); out.flush() }
                        line.startsWith("k ") -> press(line.substring(2).trim().toInt(), false)
                        line.startsWith("l ") -> press(line.substring(2).trim().toInt(), true)
                        line.startsWith("t ") -> type(line.substring(2))
                    }
                } catch (e: Throwable) { }
            }
        } catch (e: Throwable) {
        } finally {
            try { s.close() } catch (e: Throwable) { }
        }
    }

    private fun send(e: InputEvent) { inject.invoke(manager, e, 0) }

    private fun press(code: Int, long: Boolean) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        send(down)
        if (long) {
            Thread.sleep(550)
            send(KeyEvent.changeTimeRepeat(down, SystemClock.uptimeMillis(), 1, down.flags or KeyEvent.FLAG_LONG_PRESS))
        }
        send(KeyEvent.changeAction(KeyEvent.changeTimeRepeat(down, SystemClock.uptimeMillis(), 0), KeyEvent.ACTION_UP))
    }

    private fun type(text: String) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = map.getEvents(text.toCharArray()) ?: return
        for (e in events) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0))
    }
}
