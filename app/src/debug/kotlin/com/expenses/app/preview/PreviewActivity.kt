package com.expenses.app.preview

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.NoHaptics

/**
 * Debug-only screen that shows one registered preview.
 *
 *     adb shell am start -S -n com.akhilux.xpense.debug/com.expenses.app.preview.PreviewActivity \
 *         --es name home --ez dark true
 *
 * Debug builds have the app id `app.applicationId` plus ".debug". Without a name it lists every preview.
 *
 * Motion is off by default ([LocalReducedMotion] is true), so `design/tools/capture.sh` catches nothing mid-motion
 * (no pulse phase, no half-finished pop). Add `--ez motion true` to see the motion. Previews never play haptics
 * ([NoHaptics]).
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", true)
        val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val requested = intent.getStringExtra("name")
        val motion = intent.getBooleanExtra("motion", false)
        setContent {
            AppTheme(dark = dark, reducedMotion = !motion) {
                CompositionLocalProvider(LocalAppHaptics provides NoHaptics) {
                    var name by remember { mutableStateOf(requested) }
                    val entry = PreviewRegistry.find(name)
                    Box(Modifier.fillMaxSize().background(AppTheme.colors.bgCanvas)) {
                        when {
                            entry == null -> PreviewIndex(onOpen = { name = it })
                            entry.fullScreen -> entry.content()
                            else -> Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) { entry.content() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewIndex(onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().systemBarsPadding()) {
        items(PreviewRegistry.entries, key = { it.name }) { e ->
            AppText(
                text = e.name,
                style = AppTheme.type.bodyL,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(e.name) }.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}
