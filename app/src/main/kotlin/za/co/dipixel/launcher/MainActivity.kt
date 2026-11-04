package za.co.dipixel.launcher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import za.co.dipixel.launcher.data.Artwork
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Config
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.night.Sleep
import za.co.dipixel.launcher.ui.AllAppsScreen
import za.co.dipixel.launcher.ui.HomeScreen
import za.co.dipixel.launcher.ui.SettingsScreen
import za.co.dipixel.launcher.ui.SleepingScreen

enum class Screen { Home, AllApps, Settings }

class MainActivity : ComponentActivity() {

    private var config by mutableStateOf(Config())
    private var apps by mutableStateOf<List<TvApp>>(emptyList())
    private var screen by mutableStateOf(Screen.Home)
    private var sleeping by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigStore.load(this)
        za.co.dipixel.launcher.hub.HubService.start(this)
        sleeping = intent?.getBooleanExtra(Sleep.EXTRA_SLEEP, false) == true
        // changes made from a phone (profile, shows, night mode) show up instantly
        lifecycleScope.launch { ConfigStore.flow.collect { if (it != null) config = it } }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.tv.material3.MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme()) {
                    when {
                        sleeping -> SleepingScreen()
                        screen == Screen.AllApps -> {
                            BackHandler { screen = Screen.Home }
                            AllAppsScreen(config, apps, ::updateConfig)
                        }
                        screen == Screen.Settings -> {
                            BackHandler { screen = Screen.Home }
                            SettingsScreen(config, apps, ::updateConfig)
                        }
                        else -> HomeScreen(
                            config = config,
                            apps = apps,
                            onConfig = ::updateConfig,
                            onOpenAllApps = { screen = Screen.AllApps },
                            onOpenSettings = { screen = Screen.Settings }
                        )
                    }
                }
            }
        }
    }

    private fun updateConfig(c: Config) {
        config = c
        ConfigStore.save(this, c)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Sleep.EXTRA_SLEEP, false)) {
            sleeping = true
        } else if (intent.action == Intent.ACTION_ALL_APPS) {
            screen = Screen.AllApps
        } else {
            // Home button pressed while elsewhere: always land on the home screen
            screen = Screen.Home
        }
    }

    override fun onResume() {
        super.onResume()
        za.co.dipixel.launcher.hub.HubService.start(this)
        // Re-read config (sleep timer may have changed it) and refresh the app list
        config = ConfigStore.load(this)
        Thread { val list = Apps.installed(this); runOnUiThread { apps = list } }.start()
        fillArtwork()
    }

    /** Fetch backdrops and posters for any show that doesn't have them yet. */
    private fun fillArtwork() {
        if (artworkRunning) return
        artworkRunning = true
        Thread {
            try {
                val c = ConfigStore.load(this)
                c.profiles.forEachIndexed { pi, p ->
                    p.shows.forEachIndexed { si, s ->
                        if (s.backdrop.isBlank() && s.poster.isBlank()) {
                            val full = Artwork.enrich(s)
                            if (full != s) ConfigStore.update(this) { cur ->
                                cur.copy(profiles = cur.profiles.mapIndexed { i, pr ->
                                    if (i == pi) pr.copy(shows = pr.shows.mapIndexed { j, sh -> if (j == si && sh.title == s.title) full else sh }) else pr
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
            artworkRunning = false
        }.start()
    }

    @Volatile private var artworkRunning = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (sleeping) {
            if (event.action == KeyEvent.ACTION_UP) sleeping = false
            return true
        }
        if (event.keyCode in setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST) &&
            event.action == KeyEvent.ACTION_DOWN && ConfigStore.load(this).assistant == "mitv") return true
        // Hardware buttons on the Xiaomi remote that the stock launcher used to handle
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MENU -> { Apps.openSystemSettings(this); return true }
                KeyEvent.KEYCODE_TV_INPUT -> {
                    try { startActivity(Intent("android.settings.INPUT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { Apps.openSystemSettings(this) }
                    return true
                }
                KeyEvent.KEYCODE_ALL_APPS -> { screen = Screen.AllApps; return true }
                KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST -> {
                    if (ConfigStore.load(this).assistant == "mitv") {
                        startActivity(Intent(this, za.co.dipixel.launcher.voice.AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
