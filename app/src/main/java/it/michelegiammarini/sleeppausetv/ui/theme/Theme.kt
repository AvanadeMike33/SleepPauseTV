package it.michelegiammarini.sleeppausetv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF465D92),
    secondary = Color(0xFF56607A),
    tertiary = Color(0xFF725573)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB1C6FF),
    secondary = Color(0xFFBEC6DC),
    tertiary = Color(0xFFE0BBDD)
)

@Composable
fun SleepPauseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
