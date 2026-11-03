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
        try {
            server = HubServer(applicationContext).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: Exception) {
            server = null
        }
        try {
            nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "Dipixel Home"
                serviceType = SERVICE_TYPE
                port = Hub.PORT
            }
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (e: Exception) { }
        // Warm up adb now so the one-time "Allow debugging?" prompt shows while someone is at the TV
        Thread { Shell.run(applicationContext, "echo ok") }.start()
        za.co.dipixel.launcher.art.ArtRepo.refreshIfDue(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { nsd?.unregisterService(nsdListener) } catch (e: Exception) {}
        server?.stop()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Phone remote is on")
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
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HubService.start(context)
    }
}
