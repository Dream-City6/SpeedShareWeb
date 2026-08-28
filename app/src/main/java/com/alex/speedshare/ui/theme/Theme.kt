package com.alex.speedshare.ui.theme

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppThemeMode
import com.alex.speedshare.MainActivity
import com.alex.speedshare.migration.MigrationAppSelectionRegistry
import com.alex.speedshare.migration.MigrationConnectionHelpActivity
import com.alex.speedshare.migration.MigrationMediaSelectionRegistry
import com.alex.speedshare.migration.MigrationResultDetailsActivity
import com.alex.speedshare.migration.MigrationStage
import com.alex.speedshare.migration.ResilientMigrationActivity
import com.alex.speedshare.migration.ResilientMigrationController

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
                            context.startActivity(Intent(context, ResilientMigrationActivity::class.java))
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

            if (context is ResilientMigrationActivity) {
                val controller = ResilientMigrationController.get(context)
                val migrationState by controller.state.collectAsState()
                var showEarlyFinishConfirm by remember { mutableStateOf(false) }

                LaunchedEffect(migrationState.scanResult.apps) {
                    if (migrationState.scanResult.apps.isNotEmpty()) {
                        MigrationAppSelectionRegistry.sync(migrationState.scanResult.apps)
                    }
                }
                LaunchedEffect(migrationState.scanResult.files) {
                    if (migrationState.scanResult.files.isNotEmpty()) {
                        MigrationMediaSelectionRegistry.sync(migrationState.scanResult.files)
                    }
                }

                if (showEarlyFinishConfirm) {
                    AlertDialog(
                        onDismissRequest = { showEarlyFinishConfirm = false },
                        title = { Text("提前结束换机？") },
                        text = {
                            Text("已经成功迁移的内容会保留；剩余内容会标记为“未迁移”。接收端本次换机的 Temporary 断点文件也会清理。")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showEarlyFinishConfirm = false
                                    controller.finishEarlyTransfer()
                                }
                            ) { Text("提前结束") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEarlyFinishConfirm = false }) { Text("继续迁移") }
                        }
                    )
                }

                if (migrationState.stage == MigrationStage.DISCOVERY && migrationState.peers.isEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 18.dp)
                            .clickable {
                                context.startActivity(Intent(context, MigrationConnectionHelpActivity::class.java))
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        tonalElevation = 5.dp,
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "搜不到？连接帮助  ›",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (
                    migrationState.stage in setOf(MigrationStage.TRANSFERRING, MigrationStage.VERIFYING)
                ) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 18.dp)
                            .clickable { showEarlyFinishConfirm = true },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        tonalElevation = 6.dp,
                        shadowElevation = 5.dp
                    ) {
                        Text(
                            text = "提前结束",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            fontWeight = FontWeight.Black
                        )
                    }
                } else if (migrationState.stage == MigrationStage.COMPLETE) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 18.dp)
                            .clickable {
                                context.startActivity(Intent(context, MigrationResultDetailsActivity::class.java))
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        tonalElevation = 6.dp,
                        shadowElevation = 5.dp
                    ) {
                        Text(
                            text = "详细报告  ›",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}
