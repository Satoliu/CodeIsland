package com.codeisland.entry

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.codeisland.R
import com.codeisland.data.AppSettings
import com.codeisland.island.IslandNotifier
import com.codeisland.pipeline.CapturePipeline
import com.codeisland.ui.MainActivity

/**
 * 入口 3：悬浮小圆点（可选，默认关）。
 *
 * 为什么单独做成一个开关：悬浮窗会一直占着屏幕一块地方，
 * 有些人觉得很碍事。默认关着，需要的人自己去设置里打开。
 *
 * 用 [LifecycleService] 是因为它自带协程作用域，而且前台服务的
 * 生命周期管理比裸 Service 清楚。
 */
class FloatingButtonService : LifecycleService() {

    // ★ 名字不叫 windowManager：Kotlin 属性会生成 getWindowManager()，
    //   而 Android 的 Context/Service 家族里可能已经有同名方法，
    //   撞上就报 "Accidental override: same JVM signature"。
    //   加个前缀彻底避开这类问题。
    private var overlayWindowManager: WindowManager? = null
    private var dotView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        showDot()
        return START_STICKY
    }

    override fun onDestroy() {
        removeDot()
        super.onDestroy()
    }

    private fun showDot() {
        if (dotView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        overlayWindowManager = wm

        val settings = AppSettings(this)
        val size = (56 * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.floatingX
            y = settings.floatingY
        }

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_tile)
            setBackgroundResource(R.drawable.bg_floating_dot)
            alpha = 0.85f
            contentDescription = "截屏识别"
        }

        attachTouchHandler(view, params, settings)

        try {
            wm.addView(view, params)
            dotView = view
            layoutParams = params
        } catch (t: Throwable) {
            Toast.makeText(this, "悬浮窗加不上，检查一下悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /**
     * 手指行为：
     *  - 轻点一下 → 截屏
     *  - 拖动     → 挪位置，松手后把坐标记下来
     *
     * 用一个移动阈值区分「点」和「拖」，否则想挪位置时会误触发截屏。
     */
    private fun attachTouchHandler(
        view: View,
        params: WindowManager.LayoutParams,
        settings: AppSettings,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = (8 * resources.displayMetrics.density)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { overlayWindowManager?.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        settings.floatingX = params.x
                        settings.floatingY = params.y
                    } else {
                        // 拖的时候先收起小圆点，免得它自己出现在截图里
                        view.visibility = View.INVISIBLE
                        view.postDelayed({
                            CapturePipeline.trigger(applicationContext) { msg ->
                                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                            }
                            view.postDelayed({ view.visibility = View.VISIBLE }, 800)
                        }, 120)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeDot() {
        dotView?.let { v -> runCatching { overlayWindowManager?.removeView(v) } }
        dotView = null
        layoutParams = null
    }

    private fun buildForegroundNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, IslandNotifier.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("码上岛悬浮按钮在运行")
            .setContentText("点小圆点截屏识别")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "关闭", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.codeisland.action.STOP_FLOATING"
        private const val NOTIFICATION_ID = 1002
    }
}
