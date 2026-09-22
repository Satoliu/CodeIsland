package com.codeisland.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * 无障碍截屏服务。
 *
 * ★ 这个类是整个 App 能做到"一键截屏"的关键，也是**权限最大**的地方，
 *   所以这里把边界写清楚：
 *
 *   它做的事：
 *     1. [takeScreenshotNow] 调系统 API `takeScreenshot()` 截一张图。
 *        好处是**不弹「开始录制或投放」那个录屏授权框**，也不用常驻录屏。
 *     2. 可选的「双击屏幕」手势识别（[onAccessibilityEvent] 里只听触摸事件）。
 *
 *   它不做的事：
 *     ✗ 不读窗口内容 —— 没有 typeWindowContentChanged 事件，
 *       也没有重写取节点树的逻辑，拿不到你界面上的文字。
 *     ✗ 不模拟点击/滑动 —— canPerformGestures 只用来**接收**触摸事件做双击判断，
 *       没有调用过 dispatchGesture。
 *     ✗ 不记录、不上报任何东西。
 *
 * 截出来的图只在内存里过一遍，识别完立刻 recycle，**不落盘**。
 */
class CaptureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // ★ 这里**故意不碰 serviceInfo**。
        //
        //   旧版本在这个方法里做 `serviceInfo = serviceInfo.apply { eventTypes = ... }`，
        //   想把事件类型收窄成"只要触摸"，用来支持双击触发。
        //   实测（SM-S9110 / Android 16）结果是：服务被系统标记为已绑定，
        //   但 onServiceConnected 之后彻底不动了，截屏能力也一起失效。
        //
        //   教训：onServiceConnected 里重写 serviceInfo 会覆盖掉系统从 XML
        //   解析出来的 capabilities（包括 canTakeScreenshot 带来的截屏能力）。
        //   要什么能力就在 XML 里声明什么，别在运行时改。
        Log.i(TAG, "无障碍截屏服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ★ 双击触发已暂时下线（见 accessibility_service_config.xml 的说明）：
        //   canPerformGestures 会让系统以手势服务的方式初始化，导致绑定卡死。
        //   这里保留空实现，等双击功能以别的方式重新实现时再填。
    }

    override fun onInterrupt() {
        // 系统要求实现，我们不需要做任何事
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        Log.i(TAG, "无障碍截屏服务已断开")
    }

    // ------------------------------------------------------------------
    // 截屏
    // ------------------------------------------------------------------

    /**
     * 截一张全屏图。
     *
     * 失败时 [onError] 收到的是**人话**，直接可以显示给用户。
     */
    fun takeScreenshotNow(onBitmap: (Bitmap) -> Unit, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onError("系统版本太低（需要 Android 11 以上）")
            return
        }
        try {
            // ★ 第二个参数要的是 Executor，不是 Handler。
            //   Handler 在 Kotlin 里不满足 Executor 接口，传进去直接编译不过。
            //   回调会在这个 executor 所在的线程上跑 —— 用主线程，
            //   因为后面 onBitmap 要碰 UI 相关的东西（Toast / 通知 / 震动）。
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hardwareBuffer = result.hardwareBuffer
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                            if (hardware == null) {
                                onError("截屏数据读不出来")
                                return
                            }
                            // 硬件位图不能直接读像素（ML Kit / getPixel 都会挂），
                            // 先转成软件位图。
                            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                            if (software == null) {
                                onError("截屏数据转换失败")
                                return
                            }
                            // ★ 所有权约定：onBitmap 拿到的是一个**独立的软件位图**，
                            //   调用方负责用完后 recycle。这里交出去就再也不碰它，
                            //   避免调用方还在读、这边已经回收了。
                            onBitmap(software)
                        } catch (t: Throwable) {
                            Log.e(TAG, "处理截屏失败", t)
                            onError(t.message ?: "处理截屏失败")
                        } finally {
                            // 关闭硬件缓冲。位图本身由调用方回收（见上面的约定），
                            // 这里不能 recycle —— 那会让调用方拿到已回收的位图。
                            runCatching { hardwareBuffer.close() }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        onError(explainError(errorCode))
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "调用 takeScreenshot 失败", t)
            onError(t.message ?: "调用截屏接口失败")
        }
    }

    /** 把系统的错误码翻译成人能看懂的话。 */
    private fun explainError(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            "系统内部错误，稍后再试"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "截屏权限被系统收回了，去设置里把本服务关掉再打开一次"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "截得太快了，隔一秒再试"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "屏幕状态异常（可能在息屏或投屏中）"
        else -> "截屏失败（错误码 $code）"
    }

    /**
     * 给 takeScreenshot 用的 Executor。
     *
     * 用主线程：截屏回调里会碰 Toast / 通知 / 震动，这些都得在主线程上做。
     * 注意它**不是** Handler —— 这两个类型在 Kotlin 里不能互相替换。
     *
     * ★ 名字不能叫 mainExecutor：Context 本身就有 getMainExecutor()，
     *   Kotlin 属性 mainExecutor 会生成同名方法，编译器报
     *   "Accidental override: same JVM signature (getMainExecutor())"。
     *   所以这里叫 screenshotExecutor。
     */
    private val screenshotExecutor: Executor by lazy {
        ContextCompat.getMainExecutor(this)
    }

    companion object {
        private const val TAG = "CodeIsland"

        /**
         * 当前活着的服务实例。**没有连接时为 null**，调用方必须判空 ——
         * 这也是判断"无障碍开没开"的方式，比查 Settings.Secure 更准。
         */
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
