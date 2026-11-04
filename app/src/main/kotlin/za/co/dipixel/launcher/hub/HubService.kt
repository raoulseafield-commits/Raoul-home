package za.co.dipixel.launcher.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Keeps the phone remote hub running in the background on the box. */
class HubService : Service() {

    private var server: HubServer? = null
    private var nsd: NsdManager? = null
    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        startServer()
        ControlServer(applicationContext).start()
        scheduleWatchdog(this)
        try {
            nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = Hub.TV_NAME
                serviceType = SERVICE_TYPE
                port = Hub.PORT
            }
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (e: Exception) { }
        // Warm up adb now so the one-time "Allow debugging?" prompt shows while someone is at the TV
        Thread {
            val ctx = applicationContext
            Shell.run(ctx, "echo ok")
            // let Mi TV see which app is open, and read what's playing (for Continue watching)
            Shell.run(ctx, "appops set ${ctx.packageName} GET_USAGE_STATS allow; pm grant ${ctx.packageName} android.permission.RECORD_AUDIO; appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow; " +
                "cmd notification allow_listener ${ctx.packageName}/za.co.dipixel.launcher.watch.WatchListener")
            Injector.ensureStarted(ctx)
            SystemTune.applyIfNeeded(ctx)
            SystemTune.enablePlayHelper(ctx)
            SystemTune.applyAssistantIfChanged(ctx)
        }.start()
        SystemTune.registerWake(applicationContext)
        registerAppChanges()
        za.co.dipixel.launcher.art.ArtRepo.refreshIfDue(applicationContext)
    }

    private fun startServer() {
        Thread {
            // keep trying until the port is ours (it can be busy for a moment after a restart)
            for (i in 0 until 30) {
                try {
                    server = HubServer(applicationContext).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                    return@Thread
                } catch (e: Exception) { Thread.sleep(2000) }
            }
        }.start()
    }

    private fun registerAppChanges() {
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        try {
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { za.co.dipixel.launcher.data.Apps.invalidate() }
            }, f)
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server?.isAlive == false) { server?.stop(); server = null; startServer() }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        start(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { nsd?.unregisterService(nsdListener) } catch (e: Exception) {}
        server?.stop()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Mi Remote is connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    companion object {
        const val SERVICE_TYPE = "_dipixelhub._tcp."
        private const val CHANNEL = "hub"

        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, HubService::class.java)) } catch (e: Exception) {}
        }

        /** Every 15 minutes make sure the hub is alive (Android can kill anything under memory pressure). */
        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                context, 7701, Intent(context, BootReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 15 * 60_000L, 15 * 60_000L, pi)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HubService.start(context)
    }
}
