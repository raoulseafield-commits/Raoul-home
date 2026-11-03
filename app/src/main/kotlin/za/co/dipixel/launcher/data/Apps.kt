package za.co.dipixel.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

data class TvApp(
    val packageName: String,
    val label: String,
    /** Wide 16:9 banner if the app ships one, else null. */
    val banner: Drawable?,
    val icon: Drawable
)

object Apps {
    private const val SETTINGS_PACKAGE = "com.android.tv.settings"

    /** Every app that shows up on an Android TV launcher, sorted by name, self excluded. */
    fun installed(context: Context): List<TvApp> {
        val pm = context.packageManager
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<TvApp>()
        for (intent in listOf(leanback, normal)) {
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                out += TvApp(
                    packageName = pkg,
                    label = ri.loadLabel(pm).toString(),
                    banner = try { ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm) } catch (e: Exception) { null },
                    icon = ri.loadIcon(pm)
                )
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, packageName: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(context, "Can't open that app", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { context.startActivity(intent) } catch (e: Exception) {
            Toast.makeText(context, "Can't open that app", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens a show: deep link into the provider app when a URL is set, else just the app. */
    fun openShow(context: Context, show: Show) {
        if (show.url.isNotBlank()) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(show.url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (show.providerPackage.isNotBlank()) view.setPackage(show.providerPackage)
            try {
                context.startActivity(view)
                return
            } catch (e: Exception) {
                // provider didn't take the link, fall through and open the app itself
            }
        }
        if (show.providerPackage.isNotBlank()) launch(context, show.providerPackage)
    }

    fun openSystemSettings(context: Context) {
        val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (e: Exception) {
            launch(context, SETTINGS_PACKAGE)
        }
    }

    fun openHomeChooser(context: Context) {
        val intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (e: Exception) { openSystemSettings(context) }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }
}
