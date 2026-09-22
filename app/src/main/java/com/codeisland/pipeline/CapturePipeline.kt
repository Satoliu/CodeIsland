package com.codeisland.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.codeisland.capture.CaptureAccessibilityService
import com.codeisland.data.AppSettings
import com.codeisland.island.IslandNotifier
import com.codeisland.model.ParsedCode
import com.codeisland.recognize.RecognizerDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 主流程：截屏 → 识别 → 上岛。
 *
 * 三个入口（磁贴 / 快捷方式 / 悬浮按钮 / 双击）**都调 [trigger]**，
 * 保证无论从哪儿进来，行为完全一致。这是有意为之 ——
 * 上一轮做 Tasker 任务时的教训是"多个入口各写一套逻辑，
 * 改一处忘一处，最后只有其中一个能用"。
 *
 * 并发保护：用一个 [Mutex] 式的 busy 标记，防止连点导致同时截好几张图
 * 抢着上岛（最后上的那张未必是你想要的）。
 */
object CapturePipeline {

    private const val TAG = "CodeIsland"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 流程状态，界面订阅它来显示"正在识别…"。 */
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var busy = false

    sealed interface State {
        data object Idle : State
        data object Capturing : State
        data object Recognizing : State
        data class Success(val result: ParsedCode) : State
        data class Failure(val message: String) : State
    }

    /**
     * 走一遍完整流程。
     *
     * @param onToast 需要给用户一句即时反馈时回调（Toast / Snackbar 都行）。
     *                传 null 就只更新 [state]。
     */
    fun trigger(context: Context, onToast: ((String) -> Unit)? = null) {
        val appContext = context.applicationContext
        Log.i(TAG, "trigger() 被调用")

        if (busy) {
            Log.i(TAG, "上一次还没跑完，这次点击忽略")
            return
        }

        val service = CaptureAccessibilityService.instance
        if (service == null) {
            // 这是最常见的一种"坏掉了"：无障碍被系统关了。
            // 必须给一句**能直接照做**的提示，而不是干等。
            Log.w(TAG, "无障碍服务实例为 null —— 服务没连接")
            val msg = "截屏服务没开，去「设置 → 无障碍 → 码上岛」打开"
            _state.value = State.Failure(msg)
            onToast?.invoke(msg)
            return
        }
        Log.i(TAG, "无障碍服务实例正常，准备截屏")

        busy = true
        scope.launch {
            try {
                _state.value = State.Capturing
                onToast?.invoke("正在识别…")

                val bitmap = withContext(Dispatchers.Main) {
                    captureOnce(service)
                }
                Log.i(TAG, "截屏返回：${if (bitmap == null) "null（失败）" else "${bitmap.width}x${bitmap.height}"}")

                if (bitmap == null) {
                    _state.value = State.Failure("截屏失败")
                    onToast?.invoke("截屏失败")
                    return@launch
                }

                _state.value = State.Recognizing
                val dispatcher = RecognizerDispatcher(appContext)
                try {
                    val result = dispatcher.recognize(bitmap)
                    val fallback = dispatcher.lastFallbackReason
                    Log.i(TAG, "识别结果：${result?.code ?: "null"}  来源=${dispatcher.lastUsedChannel}  退回原因=$fallback")

                    if (result == null) {
                        val msg = "没认出来有码，再截清楚一点试试"
                        _state.value = State.Failure(msg)
                        onToast?.invoke(msg)
                        return@launch
                    }

                    val settings = AppSettings(appContext)
                    val notifier = IslandNotifier(appContext)

                    remember(settings, result)
                    notifier.show(result, settings.autoDismissSeconds)
                    Log.i(TAG, "已调用上岛：${result.code}")

                    if (settings.autoCopy) notifier.copyToClipboard(result.code)
                    if (settings.vibrateOnSuccess) vibrate(appContext)

                    _state.value = State.Success(result)

                    // 只有"退回本地"这件事值得打扰用户一句 ——
                    // 因为它意味着结果的可信度变了。
                    if (fallback != null && result.source == ParsedCode.Source.LOCAL) {
                        onToast?.invoke("已上岛：${result.code}（$fallback）")
                    } else {
                        onToast?.invoke("已上岛：${result.code}")
                    }
                } finally {
                    // ★ 必须放在 finally 里：上面那个 return@launch 会跳过它，
                    //   否则每认不出一次就漏一个 ML Kit 识别器。
                    dispatcher.close()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "流程出错", t)
                val msg = t.message ?: "出错了"
                _state.value = State.Failure(msg)
                onToast?.invoke(msg)
            } finally {
                busy = false
            }
        }
    }

    /** 把截屏回调包成挂起函数。截屏是异步的，必须等它回来。 */
    private suspend fun captureOnce(
        service: CaptureAccessibilityService
    ): Bitmap? = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont ->
            Log.i(TAG, "调用 takeScreenshotNow…")
            service.takeScreenshotNow(
                // 按约定，这里拿到的位图归我们所有，用完由调用方 recycle。
                // 不再多复制一份 —— 全屏位图复制一次就是好几 MB。
                onBitmap = { bmp ->
                    Log.i(TAG, "截屏成功回调：${bmp.width}x${bmp.height}")
                    if (cont.isActive) cont.resume(bmp) else bmp.recycle()
                },
                onError = { err ->
                    Log.e(TAG, "截屏失败回调：$err")
                    if (cont.isActive) cont.resume(null)
                }
            )
        }
    }

    /** 记下这一次结果，冷启动后首页还能看到。 */
    private fun remember(settings: AppSettings, result: ParsedCode) {
        settings.lastCode = result.code
        settings.lastTitle = result.displayTitle()
        settings.lastLabel = result.label
        settings.lastSource = result.source.name
        settings.lastTime = System.currentTimeMillis()
    }

    private fun vibrate(context: Context) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return

            vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            Log.i(TAG, "震动不可用：${t.message}")
        }
    }
}
