package za.co.dipixel.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Config
import za.co.dipixel.launcher.data.TvApp

@Composable
fun AllAppsScreen(config: Config, apps: List<TvApp>, onConfig: (Config) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xFF0E0E12)).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text("All apps", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text("Hold OK on an app to pin or unpin it from the home row", color = Palette.textFaint, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                Column {
                    AppTile(
                        app,
                        onClick = { Apps.launch(context, app.packageName) },
                        onLongClick = {
                            val current = favouriteApps(config, apps).map { it.packageName }
                            val next = if (app.packageName in current) current - app.packageName else current + app.packageName
                            onConfig(config.copy(favouriteApps = next))
                            Toast.makeText(context, if (app.packageName in current) "Unpinned ${app.label}" else "Pinned ${app.label}", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Text(app.label, color = Palette.textDim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                }
            }
        }
    }
}
