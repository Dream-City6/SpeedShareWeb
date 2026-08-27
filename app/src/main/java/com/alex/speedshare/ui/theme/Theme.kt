package com.alex.speedshare.ui.theme

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppThemeMode
import com.alex.speedshare.MainActivity
import com.alex.speedshare.migration.MigrationCenterActivity

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002B69),
    primaryContainer = Color(0xFF123E7A),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFF43D4E7),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF074E59),
    onSecondaryContainer = Color(0xFFB1F4FC),
    tertiary = Color(0xFFBEA7FF),
    background = Color(0xFF061426),
    onBackground = Color(0xFFE8EEF8),
    surface = Color(0xFF0D1B2E),
    onSurface = Color(0xFFE8EEF8),
    surfaceVariant = Color(0xFF17263B),
    onSurfaceVariant = Color(0xFFBAC7DA),
    outline = Color(0xFF3B4A61)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FF),
    onPrimaryContainer = Color(0xFF0A326F),
    secondary = Color(0xFF087F96),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4F2F8),
    onSecondaryContainer = Color(0xFF00363F),
    tertiary = Color(0xFF7357C7),
    background = Color(0xFFF5F8FC),
    onBackground = Color(0xFF142033),
    surface = Color.White,
    onSurface = Color(0xFF142033),
    surfaceVariant = Color(0xFFEBF1F8),
    onSurfaceVariant = Color(0xFF526176),
    outline = Color(0xFFCBD6E4)
)

private val SpeedShareShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SpeedShareTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = SpeedShareShapes
    ) {
        val context = LocalContext.current
        Box {
            content()
            if (context is MainActivity) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 18.dp)
                        .clickable {
                            context.startActivity(Intent(context, MigrationCenterActivity::class.java))
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    tonalElevation = 6.dp,
                    shadowElevation = 5.dp
                ) {
                    Text(
                        text = "⇄  一键换机  ›",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}
