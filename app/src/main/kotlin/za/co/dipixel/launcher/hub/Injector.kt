package za.co.dipixel.launcher.hub

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client side of [InjectorMain]: one open socket to it.
 * Rules that keep the remote fast:
 *  - a button press never waits for a (re)start, it falls back instantly
 *  - only one start can run at a time, at most once every 30 s
 *  - a copy that answers ping is never killed
 */
object Injector {
    const val LOG = "/data/local/tmp/mitv_injector.log"

    private val lock = Any()
    private val startLock = Any()
    private var socket: Socket? = null
    private var out: OutputStream? = null
    @Volatile var running = false
        private set
    @Volatile var lastStart = 0L
        private set
    @Volatile var lastStartResult = "not started"
        private set

    /** Blocking start, only used from background threads. */
    fun ensureStarted(context: Context): Boolean {
        if (connect()) return true
        synchronized(startLock) {
            if (connect()) return true
            if (System.currentTimeMillis() - lastStart < 30_000) return false
            lastStart = System.currentTimeMillis()
            val apk = context.applicationInfo.sourceDir
            val main = InjectorMain::class.java.name
            // [M] stops pkill matching this very shell command
            val pattern = main.replace("InjectorMain", "Injector[M]ain")
            val cmd = "pkill -f '$pattern'; sleep 0.3; (CLASSPATH=${Shell.quote(apk)} nohup app_process /system/bin $main </dev/null >$LOG 2>&1 &)"
            if (Shell.run(context, cmd) == null) { lastStartResult = "adb not available"; return false }
            for (i in 0 until 25) {
                Thread.sleep(200)
                if (connect()) { lastStartResult = "running"; return true }
            }
            lastStartResult = "started but not answering"
            return false
        }
    }

    private fun startInBackground(context: Context) {
        if (System.currentTimeMillis() - lastStart < 30_000) return
        Thread { ensureStarted(context.applicationContext) }.start()
    }

    private fun connect(): Boolean = synchronized(lock) {
        val s0 = socket
        if (s0 != null && s0.isConnected && !s0.isClosed) return true
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", InjectorMain.PORT), 250)
            s.getOutputStream().apply { write("p\n".toByteArray()); flush() }
            s.soTimeout = 600
            val ok = BufferedReader(InputStreamReader(s.getInputStream())).readLine() == "p"
            s.soTimeout = 0
            if (!ok) { s.close(); running = false; false }
            else { socket = s; out = s.getOutputStream(); running = true; true }
        } catch (e: Exception) { running = false; false }
    }

    private fun close() = synchronized(lock) {
        try { socket?.close() } catch (e: Exception) {}
        socket = null; out = null; running = false
    }

    /** Never blocks on a start: returns false right away if the injector isn't there. */
    fun send(context: Context, line: String): Boolean {
        synchronized(lock) {
            if (connect()) {
                try { out!!.write((line + "\n").toByteArray()); out!!.flush(); return true }
                catch (e: Exception) { }
            }
        }
        close()
        startInBackground(context)
        return false
    }

    fun key(context: Context, code: Int, times: Int = 1, long: Boolean = false): Boolean {
        var ok = true
        repeat(times.coerceIn(1, 10)) { ok = send(context, (if (long) "l " else "k ") + code) && ok }
        return ok
    }

    fun text(context: Context, text: String): Boolean = send(context, "t " + text.replace("\n", " "))
}
