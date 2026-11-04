package za.co.dipixel.launcher.watch

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Artwork
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.data.Show

/**
 * Lets Mi TV see what is playing (the same feed Android uses for "now playing").
 * Mi TV grants itself this through its own adb at start-up. Nothing is read from notifications.
 */
class WatchListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private val tracked = HashMap<String, MediaController.Callback>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    override fun onListenerConnected() {
        try {
            val m = getSystemService(MediaSessionManager::class.java)
            msm = m
            val me = ComponentName(this, WatchListener::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, me)
            attach(m.getActiveSessions(me))
        } catch (e: Exception) { }
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
    }

    private fun attach(list: List<MediaController>) {
        for (c in list) {
            val key = c.packageName + "@" + c.sessionToken.hashCode()
            if (key in tracked) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = Watching.check(this@WatchListener, c)
                override fun onMetadataChanged(metadata: MediaMetadata?) = Watching.check(this@WatchListener, c)
            }
            try { c.registerCallback(cb, Handler(Looper.getMainLooper())); tracked[key] = cb } catch (e: Exception) { }
            Watching.check(this, c)
        }
    }
}

/** Decides when something counts as "watching" and adds it to Continue watching. */
object Watching {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingKey: String? = null
    private val musicHints = listOf("spotify", "music", "deezer", "soundcloud", "radio", "podcast", "audible", "tidal")
    private val passiveSkip = listOf("youtube")   // YouTube clips would flood the row; explicit plays still count

    /** "The Big Bang Theory" and "Big bang theory" are the same show. */
    fun same(a: String, b: String): Boolean {
        fun k(t: String) = t.lowercase().removePrefix("the ").replace(Regex("[^a-z0-9]"), "")
        return k(a) == k(b)
    }

    fun isMusic(pkg: String) = musicHints.any { pkg.contains(it, true) }

    fun check(context: Context, c: MediaController) {
        val pkg = c.packageName ?: return
        if (pkg == context.packageName || isMusic(pkg) || passiveSkip.any { pkg.contains(it, true) }) return
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val title = titleOf(c.metadata) ?: return
        val key = "$pkg|$title"
        if (pendingKey == key) return
        pendingKey = key
        // only count it after a minute of actual playback
        handler.postDelayed({
            if (pendingKey == key && c.playbackState?.state == PlaybackState.STATE_PLAYING && titleOf(c.metadata) == title) {
                record(context.applicationContext, title, pkg)
            }
        }, 60_000)
    }

    /** Series name if the app gives one, otherwise the title, tidied up. */
    private fun titleOf(md: MediaMetadata?): String? {
        if (md == null) return null
        val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val series = listOf(MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST)
            .mapNotNull { md.getString(it) }.firstOrNull { it.isNotBlank() && it.length < 60 && it != title }
        val t = (series ?: title)
            .replace(Regex("\\b[Ss]\\d{1,2}\\s*[:.]?\\s*[Ee]\\d{1,3}\\b.*$"), "")
            .replace(Regex("\\b(Season|Episode|Ep\\.?)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .trim().trimEnd(':', '-', '·', '|').trim()
        return t.ifBlank { null }
    }

    /** Put a title at the front of the active profile's Continue watching (max 12), then fetch artwork. */
    fun record(context: Context, title: String, pkg: String, url: String = "", art: Show? = null) {
        if (isMusic(pkg)) return
        val label = Apps.installed(context).firstOrNull { it.packageName == pkg }?.label ?: ""
        var saved: Show? = null
        ConfigStore.update(context) { c ->
            val p = c.profiles.getOrNull(c.activeProfile) ?: return@update c
            val old = p.shows.firstOrNull { same(it.title, title) }
            val s = (old ?: art ?: Show(title, pkg, label)).copy(
                title = old?.title ?: title, providerPackage = pkg, providerLabel = label,
                url = url.ifBlank { old?.url ?: art?.url ?: "" }
            )
            saved = s
            val shows = (listOf(s) + p.shows.filter { !same(it.title, title) }).take(12)
            c.copy(profiles = c.profiles.mapIndexed { i, pr -> if (i == c.activeProfile) pr.copy(shows = shows) else pr })
        }
        val s = saved ?: return
        if (s.backdrop.isBlank() || s.poster.isBlank()) Thread {
            val full = Artwork.enrich(s)
            if (full != s) ConfigStore.update(context) { c ->
                c.copy(profiles = c.profiles.map { pr -> pr.copy(shows = pr.shows.map { if (it.title == s.title) full.copy(url = it.url, providerPackage = it.providerPackage, providerLabel = it.providerLabel) else it }) })
            }
        }.start()
    }
}
