package com.codeisland.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codeisland.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    initialTab: Int = 0,
) {
    var tab by remember { mutableIntStateOf(initialTab) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab == 0) stringOf(R.string.home_title) else stringOf(R.string.settings_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringOf(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringOf(R.string.tab_settings)) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        when (tab) {
            0 -> HomeTab(inner, onOpenAccessibilitySettings)
            else -> SettingsTab(
                inner,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
        }
    }
}

// ======================================================================
// 小工具
// ======================================================================

@Composable
fun stringOf(id: Int): String = LocalContext.current.getString(id)

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** 顶部的胶囊预览 —— 让用户大致知道上岛长什么样。 */
@Composable
fun IslandPreview(code: String, title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0F13), RoundedCornerShape(28.dp))
            .padding(vertical = 14.dp, horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ======================================================================
// 首页
// ======================================================================

@Composable
private fun HomeTab(padding: PaddingValues, onOpenAccessibilitySettings: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { com.codeisland.data.AppSettings(context) }

    val a11yOn = com.codeisland.capture.CaptureAccessibilityService.isConnected()
    val lastCode = settings.lastCode
    val lastTitle = settings.lastTitle.ifBlank { "取码" }
    val lastSource = settings.lastSource

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringOf(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 无障碍没开时，把这件事顶到最上面 —— 它是唯一会让整个 App 失效的原因
        if (!a11yOn) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "截屏服务还没开",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "没有它就没法截屏，整个 App 都用不了。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(stringOf(R.string.settings_a11y_open), onOpenAccessibilitySettings)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                text = stringOf(R.string.home_capture),
                onClick = {
                    com.codeisland.pipeline.CapturePipeline.trigger(context) { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringOf(R.string.home_capture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle(stringOf(R.string.home_last_result))
            SettingCard {
                if (lastCode.isBlank()) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringOf(R.string.home_no_result),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        IslandPreview(
                            code = lastCode,
                            title = lastTitle,
                            subtitle = if (lastSource == "CLOUD")
                                stringOf(R.string.home_source_cloud)
                            else stringOf(R.string.home_source_local),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    com.codeisland.island.IslandNotifier(context).copyToClipboard(lastCode)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(stringOf(R.string.home_copy)) }

                            Button(
                                onClick = {
                                    com.codeisland.island.IslandNotifier(context).show(
                                        com.codeisland.model.ParsedCode(
                                            code = lastCode,
                                            label = settings.lastLabel,
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(stringOf(R.string.home_open_island)) }
                        }
                    }
                }
            }
        }
    }
}
