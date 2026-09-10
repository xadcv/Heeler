package dev.bybee.heeler.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

private val HeelerBlue = Color(0xFF1F4E79)

@Composable
fun HeelerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // minSdk 33 guarantees dynamic (Material You) color on devices; previews and
    // host-side screenshot tests have no wallpaper to derive it from.
    val scheme = when {
        LocalInspectionMode.current -> if (dark) darkColorScheme(primary = HeelerBlue) else lightColorScheme(primary = HeelerBlue)
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
