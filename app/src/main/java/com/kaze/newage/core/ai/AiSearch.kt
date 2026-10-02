package com.kaze.newage.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 联网搜索（可插拔搜索源）。
 *
 * DeepSeek 的 chat/completions 没有服务端联网，参照 Operit"搜索能力按服务配置"的思路，
 * 这里做成独立搜索源：Tavily（国际，免费额度）与博查（国内直连，按次付费），
 * 玩家在配置里选一个并填 Key。搜索流程由 ViewModel 编排：
 * 1. [AiPrompt.searchQuerySystem] 让模型生成查询词（[extractQuery] 解析）；
 * 2. [search] 拉取结果；
 * 3. [formatResults] 拼成上下文块注入系统提示。
 * 搜索失败只降级（用本地信息回答），绝不阻塞主流程。
 */
object AiSearch {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private val json = Json { ignoreUnknownKeys = true }

    enum class Provider(val id: String, val displayName: String, val hint: String) {
        TAVILY("tavily", "Tavily", "国际可用，每月 1000 次免费额度，在 tavily.com 申请 Key"),
        BOCHA("bocha", "博查", "国内直连、按次付费，在 bochaai.com 申请 Key");

        companion object {
            fun byId(id: String): Provider = entries.firstOrNull { it.id == id } ?: TAVILY
        }
    }

    /** 一条搜索结果（标题 + 链接 + 摘要） */
    @Serializable
    data class Result(val title: String, val url: String, val snippet: String)

    fun search(provider: Provider, apiKey: String, query: String, maxResults: Int = 5): List<Result> {
        if (apiKey.isBlank()) throw RuntimeException("尚未配置搜索 API Key")
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val endpoint = when (provider) {
            Provider.TAVILY -> "https://api.tavily.com/search"
            Provider.BOCHA -> "https://api.bochaai.com/v1/web-search"
        }
        val body = buildJsonObject {
            when (provider) {
                Provider.TAVILY -> {
                    put("query", q)
                    put("search_depth", "basic")
                    put("max_results", maxResults)
                }
                Provider.BOCHA -> {
                    put("query", q)
                    put("summary", true)
                    put("count", maxResults)
                    put("freshness", "noLimit")
                }
            }
        }.toString()
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrDefault("") ?: ""
                throw RuntimeException("搜索服务返回 HTTP $code${err.take(120).ifBlank { "" }}")
            }
            val respBody = conn.inputStream.bufferedReader().use { it.readText() }
            return when (provider) {
                Provider.TAVILY -> parseTavily(respBody)
                Provider.BOCHA -> parseBocha(respBody)
            }
        } catch (e: IOException) {
            throw RuntimeException("无法连接搜索服务（${e.message ?: "网络错误"}）", e)
        } finally {
            conn.disconnect()
        }
    }

    @Serializable
    internal data class TavilyResp(val results: List<TavilyHit> = emptyList())

    @Serializable
    internal data class TavilyHit(val title: String = "", val url: String = "", val content: String = "")

    @Serializable
    internal data class BochaResp(val code: Int = 0, val msg: String? = null, val data: BochaData? = null)

    @Serializable
    internal data class BochaData(val webPages: BochaPages? = null)

    @Serializable
    internal data class BochaPages(val value: List<BochaHit> = emptyList())

    @Serializable
    internal data class BochaHit(
        val name: String = "",
        val url: String = "",
        val snippet: String = "",
        val summary: String? = null,
    )

    internal fun parseTavily(body: String): List<Result> {
        val resp = json.decodeFromString<TavilyResp>(body)
        return resp.results.map { Result(it.title.trim(), it.url.trim(), it.content.trim()) }
            .filter { it.url.isNotBlank() }
    }

    internal fun parseBocha(body: String): List<Result> {
        val resp = json.decodeFromString<BochaResp>(body)
        if (resp.code != 200) throw RuntimeException("博查搜索失败（code=${resp.code}）：${resp.msg ?: "未知错误"}")
        return resp.data?.webPages?.value.orEmpty()
            .map { Result(it.name.trim(), it.url.trim(), (it.summary ?: it.snippet).trim()) }
            .filter { it.url.isNotBlank() }
    }

    /**
     * 解析第一轮"生成查询词"的模型输出：只认 JSON {"query": "..."}（支持围栏/前后散文）；
     * 解析不出时，若整段是短短一行的纯文本就当作查询词本身，否则返回空串（不搜索）。
     */
    fun extractQuery(raw: String): String {
        val text = AiSuggestion.stripThinking(raw).trim()
        if (text.isEmpty()) return ""
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val parsed = runCatching {
                json.decodeFromString(RawQuery.serializer(), text.substring(start, end + 1))
            }.getOrNull()
            if (parsed != null) return parsed.query.trim()
        }
        return if (!text.contains('\n') && text.length <= 60 && !text.startsWith("```")) text else ""
    }

    @Serializable
    internal data class RawQuery(val query: String = "")

    /** 结果 → 注入系统上下文的文本块（标题/摘要截断，防长网页内容撑爆 token） */
    fun formatResults(query: String, results: List<Result>): String = buildString {
        append("【联网搜索结果 · 查询词：").append(query.trim()).append("】\n")
        results.forEachIndexed { i, r ->
            append(i + 1).append(". ").append(r.title.take(80)).append('\n')
            append(r.snippet.take(300)).append('\n')
            append("来源：").append(hostOf(r.url)).append('\n')
        }
    }

    internal fun hostOf(url: String): String =
        runCatching { URI(url).host ?: url }.getOrDefault(url).take(60)
}
