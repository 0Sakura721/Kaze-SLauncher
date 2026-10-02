package com.kaze.newage.core.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 本机浏览器搜索（Operit 式）：无头 WebView 加载必应结果页，页内 JS 提取条目。
 *
 * 为什么不用 HttpURLConnection 直接抓：必应对"非浏览器"特征更容易弹验证码，
 * WebView 是真浏览器内核（带 Cookie/JS），成功率高得多；代价是必须主线程创建与
 * 回调（WebView 的硬性要求），且依赖桌面版必应的 DOM 结构（`li.b_algo`，多年稳定）。
 *
 * 设计要点：
 *  - 全程不阻塞主线程：WebView 异步加载，协程经 [suspendCancellableCoroutine] 挂起等待；
 *  - 提取交给页内 JS（[AiSearch.EXTRACT_BING_JS]），Kotlin 只做纯解析（可单测）；
 *  - onPageFinished 可能因重定向多次触发：每次都尝试提取，**只有解析出结果才算完成**，
 *    一直为空就继续等（超时兜底），避免把重定向中间页当成"没有结果"；
 *  - 超时 / 协程取消都要 destroy WebView，否则每次提问泄漏一个内核实例。
 */
object BrowserSearch {

    /**
     * 桌面 UA：桌面版必应的结果 DOM（li.b_algo / h2 a / .b_caption）远比移动版稳定，
     * 且引用的是完整摘要而非移动端折叠后的短句。
     */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun searchBing(
        context: Context,
        query: String,
        maxResults: Int = 5,
        timeoutMs: Long = 25_000L,
    ): List<AiSearch.Result> = withContext(Dispatchers.Main) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        suspendCancellableCoroutine { cont ->
            val webView = try {
                WebView(context.applicationContext)
            } catch (t: Throwable) {
                // 个别 ROM 上 WebView 创建失败：按降级路径给可读错误，不闪退
                cont.resumeWith(
                    Result.failure(RuntimeException("无法创建内置浏览器内核：${t.message ?: t.javaClass.simpleName}"))
                )
                return@suspendCancellableCoroutine
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var settled = false

            fun settle(results: List<AiSearch.Result>) {
                if (settled) return
                settled = true
                mainHandler.removeCallbacksAndMessages(null)
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Throwable) {
                }
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

            mainHandler.postDelayed({ settle(emptyList()) }, timeoutMs)
            webView.loadUrl(AiSearch.buildBingSearchUrl(q))

            // 协程被取消（提问流程整体超时/界面退出）：也要清掉 WebView，泄漏的是整个内核
            cont.invokeOnCancellation {
                mainHandler.post {
                    if (!settled) {
                        settled = true
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    /** onPageFinished 后等摘要渲染的延迟（毫秒） */
    private const val EXTRACT_DELAY_MS = 800L
}
