package com.codeisland.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.codeisland.R
import com.codeisland.data.AppSettings
import com.codeisland.model.ParsedCode
import com.codeisland.ui.MainActivity

/**
 * 把识别到的码"上岛"。
 *
 * ★ 关于「灵动岛」这件事必须说清楚：
 *   三星没有苹果那种灵动岛。One UI 上对应的是**实时通知（Live notification）
 *   / Now bar** —— 它由系统的通知栏渲染在状态栏最上方区域。
 *
 *   所以本类做两层适配：
 *
 *   1. **首选：进度式实时通知**（Android 16 的 ProgressStyle，
 *      见 developer.android.com/develop/ui/views/notifications/live-update）。
 *      One UI 8 的 Now bar 会把它提升到状态栏区域，视觉上最接近"上岛"。
 *
 *   2. **兜底：高优先级常驻通知**。系统版本低、或者 OEM 不认进度样式时，
 *      至少能保证一条显眼、点一下就能复制的通知在。
 *
 *   两条路的**内容完全一样**，所以降级只影响长相，不影响功能。
 */
class IslandNotifier(private val context: Context) {

    private val settings = AppSettings(context)
    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    /**
     * 把码顶上去。
     *
     * @param autoDismissSeconds 大于 0 时，到点自动把通知收掉。
     */
    fun show(result: ParsedCode, autoDismissSeconds: Int = settings.autoDismissSeconds) {
        val title = result.displayTitle()
        val subtitle = result.displaySubtitle()

        if (!canPost()) {
            Log.w(TAG, "没有通知权限，上岛失败")
            return
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            // 副标题放在 contentText 里，Now bar 会显示成大号状态文字
            .setContentText("${result.code}  ·  $subtitle")
            .setSubText(result.code)
            .setColor(context.getColor(R.color.island_accent))
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(copyIntent(result.code))
            .addAction(copyAction(result.code))
            .addAction(dismissAction())

        attachProgressStyle(builder, result)

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            Log.e(TAG, "发通知失败", t)
        }

        if (autoDismissSeconds > 0) {
            scheduleDismiss(autoDismissSeconds)
        }
    }

    /**
     * 挂上进度式样式（实时通知 / Live Update）。
     *
     * ★ 为什么用反射、以及为什么试两个类名：
     *
     *   进度式通知有两套 API：
     *     ① `androidx.core.app.NotificationCompat.ProgressStyle`
     *        —— AndroidX 的兼容封装，**只有较新的 androidx.core 才有**。
     *     ② `android.app.Notification.ProgressStyle`
     *        —— Android 16（API 36）的平台原生类，一直都在。
     *
     *   先试 AndroidX 的（因为它能一路兼容到低版本系统），
     *   拿不到就退回平台原生的（在 API 36 上一定能用）。
     *   **两条都不行也不会崩** —— 会老老实实发一条普通通知，
     *   只是长相没那么"岛"。功能（显示码 + 点一下复制）完全不受影响。
     *
     *   用反射而不是直接写类名，是为了不让工程在编译期就绑死在
     *   某个具体的 androidx 版本上 —— 低版本依赖也能正常编译过。
     */
    private fun attachProgressStyle(builder: NotificationCompat.Builder, result: ParsedCode) {
        if (Build.VERSION.SDK_INT < 36) return

        // 先试 AndroidX 那套
        if (tryAndroidXProgressStyle(builder)) return
        // 再试平台原生那套
        if (tryPlatformProgressStyle(builder)) return

        Log.i(TAG, "进度式样式不可用，本次用普通通知（功能不受影响）")
    }

    /** 方案①：androidx.core 的 ProgressStyle。成功返回 true。 */
    private fun tryAndroidXProgressStyle(builder: NotificationCompat.Builder): Boolean = try {
        val styleClass = Class.forName("androidx.core.app.NotificationCompat\$ProgressStyle")
        val style = styleClass.getConstructor().newInstance()

        styleClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, 60)

        // setProgressSegments(List<ProgressSegment>)：最多 3 段，我们只放一段
        val segmentBuilderClass =
            Class.forName("androidx.core.app.NotificationCompat\$ProgressStyle\$ProgressSegment\$Builder")
        val pointsClass =
            Class.forName("androidx.core.app.NotificationCompat\$ProgressStyle\$Point")

        val points = ArrayList<Any>().apply {
            add(pointsClass.getConstructor(Int::class.javaPrimitiveType).newInstance(60))
        }

        val segBuilder = segmentBuilderClass.getConstructor().newInstance()
        segmentBuilderClass.getMethod("setColor", Int::class.javaPrimitiveType)
            .invoke(segBuilder, context.getColor(R.color.island_accent))
        segmentBuilderClass.getMethod("setPoints", java.util.List::class.java)
            .invoke(segBuilder, points)
        val segment = segmentBuilderClass.getMethod("build").invoke(segBuilder)

        styleClass.getMethod("setProgressSegments", java.util.List::class.java)
            .invoke(style, ArrayList<Any>().apply { add(segment) })

        styleClass.getMethod("setProgressTrackerIcon", Icon::class.java)
            .invoke(style, Icon.createWithResource(context, R.drawable.ic_tile))

        val asStyle = style as? NotificationCompat.Style ?: return false
        builder.setStyle(asStyle)
        true
    } catch (t: Throwable) {
        // 类不存在 / 方法签名对不上 —— 都属于"这个版本没有"，正常换下一条路
        Log.i(TAG, "androidx 进度样式不可用：${t.javaClass.simpleName}")
        false
    }

    /** 方案②：Android 16 平台原生的 Notification.ProgressStyle。 */
    private fun tryPlatformProgressStyle(builder: NotificationCompat.Builder): Boolean = try {
        val styleClass = Class.forName("android.app.Notification\$ProgressStyle")
        val style = styleClass.getConstructor().newInstance()

        styleClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, 60)
        styleClass.getMethod("setProgressTrackerIcon", Icon::class.java)
            .invoke(style, Icon.createWithResource(context, R.drawable.ic_tile))
        styleClass.getMethod("setProgressPoints", java.util.List::class.java)
            .invoke(style, ArrayList<Any>().apply {
                // 平台版用 IntArray 表示进度点
                add(intArrayOf(60))
            })

        val asStyle = style as? NotificationCompat.Style ?: return false
        builder.setStyle(asStyle)
        true
    } catch (t: Throwable) {
        Log.i(TAG, "平台进度样式不可用：${t.javaClass.simpleName}")
        false
    }

    /** 收起。 */
    fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
        handler?.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------

    private var handler: android.os.Handler? = null

    private fun scheduleDismiss(seconds: Int) {
        val h = handler ?: android.os.Handler(android.os.Looper.getMainLooper()).also { handler = it }
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ dismiss() }, seconds * 1000L)
    }

    /** 点通知本体 = 复制号码。取餐时最顺手的动作就是复制。 */
    private fun copyIntent(code: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_COPY_CODE, code)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_COPY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun copyAction(code: String): NotificationCompat.Action {
        val intent = Intent(context, IslandActionReceiver::class.java).apply {
            action = IslandActionReceiver.ACTION_COPY
            putExtra(EXTRA_COPY_CODE, code)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_ACTION_COPY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            // ★ 这里要的是 IconCompat，不是 android.graphics.drawable.Icon。
            //   传 Icon 会报 "None of the following candidates is applicable"。
            IconCompat.createWithResource(context, R.drawable.ic_tile),
            "复制",
            pi
        ).build()
    }

    private fun dismissAction(): NotificationCompat.Action {
        val intent = Intent(context, IslandActionReceiver::class.java).apply {
            action = IslandActionReceiver.ACTION_DISMISS
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_ACTION_DISMISS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_tile),
            "收起",
            pi
        ).build()
    }

    private fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ISLAND,
                context.getString(R.string.notif_channel_island),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_island_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    /**
     * 复制号码到剪贴板。
     *
     * 注意：Android 13 起系统会自己弹一个"已复制"的浮层，
     * 所以这里**不再自己弹 Toast**，否则会出现两个提示叠在一起。
     */
    fun copyToClipboard(code: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("取码", code))
    }

    companion object {
        private const val TAG = "CodeIsland"

        const val CHANNEL_ISLAND = "codeisland_island"
        const val CHANNEL_SERVICE = "codeisland_service"

        const val NOTIFICATION_ID = 1001
        const val EXTRA_COPY_CODE = "copy_code"

        private const val REQ_COPY = 2001
        private const val REQ_ACTION_COPY = 2002
        private const val REQ_ACTION_DISMISS = 2003
    }
}
