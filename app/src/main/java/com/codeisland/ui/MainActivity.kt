package com.codeisland.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.codeisland.island.IslandNotifier
import com.codeisland.pipeline.CapturePipeline

/**
 * 主界面。两个标签页：首页（截屏 + 看上次结果）和设置（触发方式 + API Key）。
 *
 * 也是处理通知权限申请的地方 —— Android 13 起没有 POST_NOTIFICATIONS
 * 就发不出通知，而发不出通知就谈不上"上岛"，所以进 App 就要问。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast("没有通知权限就上不了岛，去系统设置里允许通知")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        askNotificationPermission()

        // 先处理快捷方式带来的动作，再渲染界面
        var startTab = 0
        val action = intent?.action
        when (action) {
            ACTION_CAPTURE -> {
                // 侧键菜单点了「捕获屏幕」：立刻走一遍截屏识别，然后切到首页看结果
                CapturePipeline.trigger(applicationContext) { msg ->
                    toast(msg)
                }
                startTab = 0
            }
            ACTION_SETTINGS -> startTab = 1
            else -> handleIntentExtras(intent)
        }

        setContent {
            CodeIslandTheme {
                MainScreen(
                    initialTab = startTab,
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onRequestNotificationPermission = { askNotificationPermission() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        when (intent.action) {
            ACTION_CAPTURE -> CapturePipeline.trigger(applicationContext) { msg -> toast(msg) }
            ACTION_SETTINGS -> { /* 界面已经在设置页，不用动 */ }
            else -> handleIntentExtras(intent)
        }
    }

    /**
     * 从"上岛通知"点进来时，说明用户就是想复制这个号 ——
     * 直接把号码塞进剪贴板，省得他再点一次。
     */
    private fun handleIntentExtras(intent: Intent?) {
        val code = intent?.getStringExtra(IslandNotifier.EXTRA_COPY_CODE).orEmpty()
        if (code.isNotBlank()) {
            IslandNotifier(this).copyToClipboard(code)
            toast("已复制 $code")
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { toast("打不开无障碍设置，请手动进：设置 → 无障碍") }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast("打不开悬浮窗设置") }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 侧键菜单「捕获屏幕」带过来的 action，要和 res/xml/shortcuts.xml 一致 */
        const val ACTION_CAPTURE = "com.codeisland.action.CAPTURE"

        /** 侧键菜单「设置」带过来的 action */
        const val ACTION_SETTINGS = "com.codeisland.action.SETTINGS"
    }
}

private val IslandColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF06281B),
    primaryContainer = Color(0xFF10402C),
    onPrimaryContainer = Color(0xFFB6F5DA),
    secondary = Color(0xFF8FD8C0),
    background = Color(0xFF0D0F13),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF14171D),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1D2129),
    onSurfaceVariant = Color(0xFFA9B2C0),
    outline = Color(0xFF39404B),
    error = Color(0xFFFF6B6B),
)

@Composable
fun CodeIslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IslandColors, content = content)
}
