package com.kaze.newage.core.ai

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 本机浏览器搜索（Operit 式）：WebView 加载必应结果页，页内 JS 提取条目。
 *
 * 两种挂载方式（按悬浮窗权限自动选择）：
 *  - **真实窗口**（已授予「显示在其他应用上层」= `SYSTEM_ALERT_WINDOW`，manifest 里已声明）：
 *    1px 透明、不可触摸的 overlay 窗口。WebView 拿到真窗口后渲染器以全优先级调度，
 *    不会被 ROM 当后台冻结 —— 必应页面的 JS 与网络请求最稳（vivo 等激进后台管理的机器上
 *    差别明显）。这是**这个权限在本应用里唯一的用途**；它不可触摸不可聚焦、无 JS 接口、
 *    不允许外跳，不授予时自动回退下面的无头模式。
 *  - **无头模式**（未授权，默认兜底）：WebView 不挂窗口直接加载。多数设备可用，
 *    个别 ROM 会限流后台渲染导致超时。
 *
 * 其余设计要点：
 *  - 全程不阻塞主线程：WebView 异步加载，协程经 [suspendCancellableCoroutine] 挂起等待；
 *  - 提取交给页内 JS（[AiSearch.EXTRACT_BING_JS]），Kotlin 只做纯解析（可单测）；
 *  - onPageFinished 可能因重定向多次触发：每次都尝试提取，**只有解析出结果才算完成**，
 *    一直为空就继续等（超时兜底），避免把重定向中间页当成"没有结果"；
 *  - 超时 / 协程取消都要摘除窗口并 destroy WebView，否则泄漏一个内核实例
 *    （overlay 窗口泄漏的后果更严重：系统会一直显示那个 1px 窗口）。
 */
object BrowserSearch {

    /**
     * 桌面 UA：桌面版必应的结果 DOM（li.b_algo / h2 a / .b_caption）远比移动版稳定，
     * 且引用的是完整摘要而非移动端折叠后的短句。
     */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** onPageFinished 后等摘要渲染的延迟（毫秒） */
    private const val EXTRACT_DELAY_MS = 800L

    suspend fun searchBing(
        context: Context,
        query: String,
        maxResults: Int = 5,
        timeoutMs: Long = 25_000L,
    ): List<AiSearch.Result> = withContext(Dispatchers.Main) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val appContext = context.applicationContext
        suspendCancellableCoroutine { cont ->
            val webView = try {
                WebView(appContext)
            } catch (t: Throwable) {
                // 个别 ROM 上 WebView 创建失败：按降级路径给可读错误，不闪退
                cont.resumeWith(
                    Result.failure(RuntimeException("无法创建内置浏览器内核：${t.message ?: t.javaClass.simpleName}"))
                )
                return@suspendCancellableCoroutine
            }

            val windowManager =
                appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            // 真窗口模式：1px 透明、不可触摸不可聚焦 —— 用户看不到也摸不到，
            // 但 WebView 拿到真 window surface，渲染器全优先级（悬浮窗权限的价值所在）
            var attached = false
            val overlayParams = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var settled = false

            fun detach() {
                if (attached) {
                    attached = false
                    runCatching { windowManager?.removeView(webView) }
                }
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Throwable) {
                }
            }

            fun settle(results: List<AiSearch.Result>) {
                if (settled) return
                settled = true
                mainHandler.removeCallbacksAndMessages(null)
                detach()
                if (cont.isActive) cont.resumeWith(Result.success(results))
            }

            // 提取动作延后一小段：onPageFinished 时个别异步渲染的摘要可能还没就位
            fun extractSoon(view: WebView) {
                mainHandler.postDelayed({
                    if (settled) return@postDelayed
                    view.evaluateJavascript(AiSearch.EXTRACT_BING_JS) { raw ->
                        val parsed = AiSearch.parseBingExtraction(raw)
                        // 结果非空才结算；空结果继续等下一次 onPageFinished / 超时
                        if (parsed.isNotEmpty()) settle(parsed.take(maxResults))
                    }
                }, EXTRACT_DELAY_MS)
            }

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = DESKTOP_UA
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!settled) extractSoon(view)
                }
            }

            // 授权了悬浮窗 → 真窗口挂载；addView 万一失败（权限被收回等）静默回退无头
            if (Settings.canDrawOverlays(appContext)) {
                try {
                    windowManager?.addView(webView, overlayParams)
                    attached = true
                } catch (_: Throwable) {
                    attached = false
                }
            }

            mainHandler.postDelayed({ settle(emptyList()) }, timeoutMs)
            webView.loadUrl(AiSearch.buildBingSearchUrl(q))

            // 协程被取消（提问流程整体超时/界面退出）：也要摘窗 + 销毁，
            // overlay 窗口泄漏系统会一直挂着那个 1px 窗口
            cont.invokeOnCancellation {
                mainHandler.post {
                    if (!settled) {
                        settled = true
                        detach()
                    }
                }
            }
        }
    }
}
