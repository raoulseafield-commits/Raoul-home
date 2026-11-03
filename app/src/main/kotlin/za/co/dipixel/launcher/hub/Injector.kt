package za.co.dipixel.launcher.hub

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Client side of [InjectorMain]: keeps one open socket to it and starts it when needed. */
object Injector {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    @Volatile var running = false
        private set

    /** Kill any old copy (e.g. from a previous version) and start a fresh one through adb. */
    fun restart(context: Context): Boolean {
        close()
        val apk = context.applicationInfo.sourceDir
        val main = InjectorMain::class.java.name
        // the [M] stops pkill matching this very shell command and killing itself
        val pattern = main.replace("InjectorMain", "Injector[M]ain")
        val cmd = "pkill -f '$pattern'; sleep 0.3; (CLASSPATH=${Shell.quote(apk)} nohup app_process /system/bin $main </dev/null >/dev/null 2>&1 &)"
        Shell.run(context, cmd) ?: return false
        for (i in 0 until 20) {
            Thread.sleep(150)
            if (connect()) return true
        }
        return false
    }

    @Synchronized
    private fun connect(): Boolean {
        if (socket?.isConnected == true && socket?.isClosed == false) return true
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", InjectorMain.PORT), 300)
            // ping so we know it is really the injector
            s.getOutputStream().apply { write("p\n".toByteArray()); flush() }
            s.soTimeout = 800
            val ok = BufferedReader(InputStreamReader(s.getInputStream())).readLine() == "p"
            s.soTimeout = 0
            if (!ok) { s.close(); false } else { socket = s; out = s.getOutputStream(); running = true; true }
        } catch (e: Exception) { running = false; false }
    }

    @Synchronized
    private fun close() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null; out = null; running = false
    }

    private fun write(line: String): Boolean {
        synchronized(this) {
            if (connect()) {
                try { out!!.write((line + "\n").toByteArray()); out!!.flush(); return true }
                catch (e: Exception) { close() }
            }
        }
        return false
    }

    /** Send a command; if the injector is gone, start it once and retry. */
    fun send(context: Context, line: String): Boolean {
        if (write(line)) return true
        if (restart(context)) return write(line)
        return false
    }

    fun key(context: Context, code: Int, times: Int = 1, long: Boolean = false): Boolean {
        var ok = true
        repeat(times.coerceIn(1, 10)) { ok = send(context, (if (long) "l " else "k ") + code) && ok }
        return ok
    }

    fun text(context: Context, text: String): Boolean = send(context, "t " + text.replace("\n", " "))
}
