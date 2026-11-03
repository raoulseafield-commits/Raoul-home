package za.co.dipixel.launcher.hub

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * The box has adb over the network enabled (that's how the launcher got installed).
 * The launcher connects to its own adb on 127.0.0.1:5555 so it can press remote keys
 * inside other apps, which a normal app is not allowed to do. The first connection
 * shows "Allow debugging?" on the TV once: tick Always allow.
 */
object Shell {
    private var dadb: Dadb? = null
    @Volatile var lastOk: Boolean = false
        private set

    @Synchronized
    fun run(context: Context, command: String): String? {
        repeat(2) {
            try {
                val d = dadb ?: connect(context).also { dadb = it }
                val r = d.shell(command)
                lastOk = true
                return r.output + r.errorOutput
            } catch (e: Throwable) {
                try { dadb?.close() } catch (_: Throwable) {}
                dadb = null
                lastOk = false
            }
        }
        return null
    }

    private fun connect(context: Context): Dadb {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub))
    }

    /** Wrap a value for the device shell. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun key(context: Context, keyCode: Int, times: Int = 1): Boolean {
        val cmd = (1..times.coerceIn(1, 10)).joinToString(" && ") { "input keyevent $keyCode" }
        return run(context, cmd) != null
    }

    fun text(context: Context, text: String): Boolean {
        // input text needs spaces as %s
        val t = text.replace(" ", "%s")
        return run(context, "input text " + quote(t)) != null
    }
}
