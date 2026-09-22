package com.codeisland.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.codeisland.R
import com.codeisland.data.ApiKeyStore
import com.codeisland.data.AppSettings
import com.codeisland.recognize.RecognizerDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 布局顺序是有讲究的：**触发方式 → AI 识别 → 上岛显示 → 关于**。
 * 用户第一次装完，最需要做的两件事（开无障碍、填 Key）都在最前面，
 * 不用往下翻。
 */
@Composable
fun SettingsTab(
    padding: PaddingValues,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val keyStore = remember { ApiKeyStore(context) }
    val scope = rememberCoroutineScope()

    // ---- 触发方式 ----
    var a11yOn by remember { mutableStateOf(com.codeisland.capture.CaptureAccessibilityService.isConnected()) }
    var doubleTap by remember { mutableStateOf(settings.doubleTapEnabled) }
    var floating by remember { mutableStateOf(settings.floatingButtonEnabled) }
    var keepAlive by remember { mutableStateOf(settings.keepAlive) }

    // 每次回到这个界面都重新读一遍无障碍状态 —— 用户可能刚去系统设置里开过
    LaunchedEffect(Unit) {
        a11yOn = com.codeisland.capture.CaptureAccessibilityService.isConnected()
    }

    // ---- AI ----
    var useCloud by remember { mutableStateOf(settings.useCloud) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var apiKey by remember { mutableStateOf(keyStore.load()) }
    var showKey by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf(settings.timeoutSeconds.toString()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // ---- 显示 ----
    var autoDismiss by remember { mutableStateOf(settings.autoDismissSeconds.toString()) }
    var vibrate by remember { mutableStateOf(settings.vibrateOnSuccess) }
    var autoCopy by remember { mutableStateOf(settings.autoCopy) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {

        // ============================================================
        // 触发方式
        // ============================================================
        item { SectionTitle(stringOf(R.string.settings_section_trigger)) }
        item {
            SettingCard {
                SettingRow(
                    title = stringOf(R.string.settings_a11y),
                    subtitle = if (a11yOn) stringOf(R.string.settings_a11y_on)
                    else stringOf(R.string.settings_a11y_off),
                    trailing = {
                        if (!a11yOn) {
                            TextButton(onClick = onOpenAccessibilitySettings) {
                                Text(stringOf(R.string.settings_a11y_open))
                            }
                        } else {
                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                )

                SettingRow(
                    title = stringOf(R.string.settings_tile),
                    subtitle = stringOf(R.string.settings_tile_hint),
                )

                SettingRow(
                    title = stringOf(R.string.settings_shortcut),
                    subtitle = "长按桌面 → 小组件 → 码上岛",
                    trailing = {
                        TextButton(onClick = {
                            addShortcut(context)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.settings_shortcut_added),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }) { Text(stringOf(R.string.settings_shortcut_add)) }
                    }
                )

                SettingRow(
                    title = stringOf(R.string.settings_double_tap),
                    subtitle = stringOf(R.string.settings_double_tap_hint),
                    trailing = {
                        Switch(checked = doubleTap, onCheckedChange = {
                            doubleTap = it
                            settings.doubleTapEnabled = it
                        })
                    }
                )

                SettingRow(
                    title = stringOf(R.string.settings_floating),
                    subtitle = stringOf(R.string.settings_floating_hint),
                    trailing = {
                        Switch(
                            checked = floating,
                            onCheckedChange = { want ->
                                // 没悬浮窗权限就先带用户去授权。
                                // 这里不能把开关打开 —— 打开了也加不上视图，
                                // 用户会以为"开了但没反应"。
                                if (want && !canDrawOverlays(context)) {
                                    onOpenOverlaySettings()
                                } else {
                                    floating = want
                                    settings.floatingButtonEnabled = want
                                    toggleFloatingService(context, want)
                                }
                            }
                        )
                    }
                )

                SettingRow(
                    title = stringOf(R.string.settings_keep_alive),
                    subtitle = stringOf(R.string.settings_keep_alive_hint),
                    trailing = {
                        Switch(checked = keepAlive, onCheckedChange = {
                            keepAlive = it
                            settings.keepAlive = it
                        })
                    }
                )

                SettingRow(
                    title = "通知权限",
                    subtitle = "上岛要靠通知，没权限就显示不出来",
                    trailing = {
                        TextButton(onClick = onRequestNotificationPermission) { Text("申请") }
                    }
                )
            }
        }

        // ============================================================
        // AI 识别
        // ============================================================
        item { SectionTitle(stringOf(R.string.settings_section_ai)) }
        item {
            SettingCard {
                SettingRow(
                    title = stringOf(R.string.settings_use_cloud),
                    subtitle = stringOf(R.string.settings_use_cloud_hint),
                    trailing = {
                        Switch(checked = useCloud, onCheckedChange = {
                            useCloud = it
                            settings.useCloud = it
                        })
                    }
                )
                if (!keyStore.isAvailable()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "⚠ 这台设备的加密存储不可用，API Key 无法安全保存。请先用本地 OCR。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item {
            SettingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringOf(R.string.settings_base_url)) },
                        placeholder = { Text(stringOf(R.string.settings_base_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(stringOf(R.string.settings_model)) },
                        placeholder = { Text(stringOf(R.string.settings_model_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringOf(R.string.settings_api_key)) },
                        placeholder = { Text(stringOf(R.string.settings_api_key_hint)) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) stringOf(R.string.settings_hide_key)
                                    else stringOf(R.string.settings_show_key)
                                )
                            }
                        },
                    )

                    OutlinedTextField(
                        value = timeout,
                        onValueChange = { timeout = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text(stringOf(R.string.settings_timeout)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    Text(
                        "★ 图片识别需要**支持看图的模型**。deepseek-chat 是纯文本模型，\n" +
                                "传图片会报错。可以换成 qwen-vl-max、glm-4v、gpt-4o 这类视觉模型。\n" +
                                "没配 Key 或模型不支持时，会自动退回本地 OCR，功能不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                settings.baseUrl = baseUrl
                                settings.model = model
                                settings.timeoutSeconds = timeout.toIntOrNull() ?: 25
                                keyStore.save(apiKey)
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_api_key_saved),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存") }

                        OutlinedButton(
                            onClick = {
                                // 先存再测 —— 否则测的是旧配置，很容易白测一轮
                                settings.baseUrl = baseUrl
                                settings.model = model
                                settings.timeoutSeconds = timeout.toIntOrNull() ?: 25
                                keyStore.save(apiKey)

                                testing = true
                                testResult = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        RecognizerDispatcher(context).testCloudConnection()
                                    }
                                    testResult = r.ok to r.message
                                    testing = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !testing,
                        ) {
                            if (testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (testing) stringOf(R.string.settings_testing)
                            else stringOf(R.string.settings_test_api))
                        }
                    }

                    testResult?.let { (ok, msg) ->
                        Text(
                            (if (ok) "✓ " else "✗ ") + msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }

                    if (apiKey.isNotBlank()) {
                        TextButton(onClick = {
                            apiKey = ""
                            keyStore.clear()
                            testResult = null
                        }) { Text(stringOf(R.string.settings_api_key_clear)) }
                    }
                }
            }
        }

        // ============================================================
        // 上岛显示
        // ============================================================
        item { SectionTitle(stringOf(R.string.settings_section_island)) }
        item {
            SettingCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = autoDismiss,
                        onValueChange = { autoDismiss = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text(stringOf(R.string.settings_auto_dismiss)) },
                        supportingText = { Text(stringOf(R.string.settings_auto_dismiss_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                SettingRow(
                    title = stringOf(R.string.settings_vibrate),
                    trailing = {
                        Switch(checked = vibrate, onCheckedChange = {
                            vibrate = it
                            settings.vibrateOnSuccess = it
                        })
                    }
                )
                SettingRow(
                    title = stringOf(R.string.settings_copy_auto),
                    trailing = {
                        Switch(checked = autoCopy, onCheckedChange = {
                            autoCopy = it
                            settings.autoCopy = it
                        })
                    }
                )
            }
        }

        // ============================================================
        // 关于
        // ============================================================
        item { SectionTitle(stringOf(R.string.settings_section_about)) }
        item {
            SettingCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringOf(R.string.settings_about_text).format(appVersion(context)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // 离开界面时把还没落盘的数值设置存一下
    LaunchedEffect(autoDismiss) {
        settings.autoDismissSeconds = autoDismiss.toIntOrNull() ?: 0
    }
}

// ======================================================================
// 平台相关的小工具
// ======================================================================

private fun addShortcut(context: android.content.Context) {
    val intent = android.content.Intent(context, CaptureShortcutActivity::class.java).apply {
        action = "com.codeisland.action.CAPTURE"
    }

    val shortcut = android.content.pm.ShortcutInfo.Builder(context, "codeisland_capture")
        .setShortLabel("截屏上岛")
        .setLongLabel("码上岛 · 截屏识别取码")
        .setIcon(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_tile))
        .setIntent(intent)
        .build()

    val mgr = context.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
    if (mgr.isRequestPinShortcutSupported) {
        mgr.requestPinShortcut(shortcut, null)
    } else {
        android.widget.Toast.makeText(
            context, "这个桌面不支持自动添加快捷方式，请手动长按桌面添加", android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

private fun canDrawOverlays(context: android.content.Context): Boolean =
    android.provider.Settings.canDrawOverlays(context)

private fun toggleFloatingService(context: android.content.Context, on: Boolean) {
    val intent = android.content.Intent(context, com.codeisland.entry.FloatingButtonService::class.java)
    if (on) {
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    } else {
        context.stopService(intent)
    }
}

private fun appVersion(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
} catch (t: Throwable) {
    "1.0.0"
}
