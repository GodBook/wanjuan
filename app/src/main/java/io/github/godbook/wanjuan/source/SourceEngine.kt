package io.github.godbook.wanjuan.source

import android.content.Context
import io.github.godbook.wanjuan.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class ImportCandidate(val source: Source, val warnings: List<String>)
data class SearchEvent(val source: Source, val books: List<Book> = emptyList(), val error: String? = null)
class SourceEngine(context: Context) {
    private val scripts = Scripts(context)
    private val builtins = BuiltinSources(context)
    private val hosts = ConcurrentHashMap<String,Semaphore>()
    fun inspect(text: String): List<ImportCandidate> {
        require(text.length <= 8*1024*1024) { "书源文件超过 8MB" }
        val raw = text.trim().removePrefix("\uFEFF")
        val list = if(raw.startsWith("[")) JSONArray(raw).objects() else listOf(JSONObject(raw))
        require(list.size <= 1000) { "单次最多导入 1000 个书源" }
        return list.map { j ->
            val url = j.text("bookSourceUrl").toHttpUrl()
            val warnings = mutableListOf<String>()
            if(j.text("searchUrl").isBlank()) warnings += "缺少搜索地址"
            listOf("ruleSearch","ruleToc","ruleContent").forEach { if(j.optJSONObject(it) == null) warnings += "缺少 $it" }
            if(j.toString().contains("java.")) warnings += "不支持 Java 宿主方法"
            if(j.toString().contains("webView",true)) warnings += "不支持动态网页"
            if(j.text("loginUrl").isNotBlank()) warnings += "登录流程不支持"
            if(j.toString().contains("@put:") || j.toString().contains("@get:")) warnings += "变量存取规则尚不支持"
            val id = BuiltinSources.defaults().find { it.url==url.toString() && it.json.optString("wanjuanProvider")==j.optString("wanjuanProvider") }?.id ?: sha256(url.toString().toByteArray()).take(32)
            if(id.startsWith("builtin-")) warnings.clear()
            ImportCandidate(Source(id,j.toString(),j.optBoolean("enabled",true),false,if(warnings.isEmpty()) "待检测 · 常用规则" else warnings.joinToString("；")),warnings)
        }
    }
    fun search(query: String, sources: List<Source>) = channelFlow {
        val gate = Semaphore(6)
        sources.filter { it.enabled }.forEach { source -> launch {
            gate.withPermit {
                try { val found = withTimeout(12000) { searchOne(query,source) }; send(SearchEvent(source,found)) }
                catch (e: TimeoutCancellationException) { send(SearchEvent(source,error = "搜索超时")) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { send(SearchEvent(source,error = e.message ?: "解析失败")) }
            }
        } }
    }.flowOn(Dispatchers.IO)
    private suspend fun request(source: Source, expression: String, query: String = ""): String {
        val expanded = expression.replace("{{key}}",URLEncoder.encode(query,"UTF-8")).replace("{{page}}","1").replace("{{searchKey}}",URLEncoder.encode(query,"UTF-8"))
        val split = expanded.indexOf(",{\"").takeIf { it >= 0 } ?: expanded.indexOf(",{ ").takeIf { it >= 0 }
        val path = if(split != null) expanded.substring(0,split) else expanded
        val options = if(split != null) JSONObject(expanded.substring(split+1)) else JSONObject()
        val url = source.url.toHttpUrl().resolve(path)?.toString() ?: error("请求地址无效")
        val headers = mutableMapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) WanJuan/0.1")
        val header = source.json.opt("header")
        val headerJson = when(header) { is JSONObject -> header; is String -> runCatching { JSONObject(header) }.getOrNull(); else -> null }
        headerJson?.keys()?.forEach { headers[it] = headerJson.getString(it) }
        val method = options.text("method","GET").uppercase()
        require(method in listOf("GET","POST")) { "不支持的请求方法" }
        val body = if(method == "POST") options.text("body").toRequestBody(options.text("type","application/x-www-form-urlencoded").toMediaType()) else null
        return hosts.getOrPut(url.toHttpUrl().host) { Semaphore(2) }.withPermit { Network(source.http).text(url,headers,method,body) }
    }
    private suspend fun value(input: String, rule: String, base: String): String {
        if(rule.startsWith("@js:")) return scripts.execute(rule.removePrefix("@js:"), input)
        val index = rule.indexOf("@js:")
        return if(index > 0) scripts.execute(rule.substring(index+4),Rules.value(input,rule.substring(0,index),base)) else Rules.value(input,rule,base)
    }
    suspend fun searchOne(query: String, source: Source): List<Book> {
        if(BuiltinSources.provider(source)!=null) return builtins.search(query,source)
        val j = source.json
        val html = request(source,j.getString("searchUrl"),query)
        val rules = j.getJSONObject("ruleSearch")
        return Rules.nodes(html,rules.getString("bookList"),source.url).take(200).mapNotNull { node ->
            val name = value(node,rules.text("name"),source.url)
            val url = value(node,rules.text("bookUrl"),source.url)
            if(name.isBlank() || url.isBlank()) null else Book(title=name, author=value(node,rules.text("author"),source.url),sourceId=source.id,
                url=source.url.toHttpUrl().resolve(url)?.toString() ?: url,intro=value(node,rules.text("intro"),source.url),cover=value(node,rules.text("coverUrl"),source.url))
        }
    }
    suspend fun catalog(book: Book, source: Source): Book {
        if(BuiltinSources.provider(source)!=null) return builtins.catalog(book,source)
        val info = source.json.optJSONObject("ruleBookInfo") ?: JSONObject()
        val detail = request(source,book.url)
        val toc = value(detail,info.text("tocUrl"),book.url).ifBlank { book.url }
        val tocUrl = book.url.toHttpUrl().resolve(toc)?.toString() ?: toc
        var page = tocUrl
        val seen = mutableSetOf<String>()
        val chapters = linkedMapOf<String,Chapter>()
        val rules = source.json.getJSONObject("ruleToc")
        repeat(30) {
            if(page.isBlank() || !seen.add(page)) return@repeat
            val html = if(page == book.url && seen.size == 1) detail else request(source,page)
            Rules.nodes(html,rules.getString("chapterList"),page).forEach { node ->
                val title = value(node,rules.text("chapterName"),page)
                val path = value(node,rules.text("chapterUrl"),page)
                if(title.isNotBlank() && path.isNotBlank()) {
                    val url = page.toHttpUrl().resolve(path)?.toString() ?: path
                    chapters[url] = Chapter(id=sha256((book.sourceId+url).toByteArray()).take(32),title=title,url=url)
                }
            }
            val next = value(html,rules.text("nextTocUrl"),page)
            page = if(next.isBlank()) "" else page.toHttpUrl().resolve(next)?.toString().orEmpty()
        }
        require(chapters.isNotEmpty()) { "目录为空，请检查目录规则或更换书源" }
        return book.copy(tocUrl=tocUrl,chapters=chapters.values.toList(),intro=value(detail,info.text("intro"),book.url).ifBlank { book.intro })
    }
    suspend fun content(chapter: Chapter, source: Source): String {
        if(BuiltinSources.provider(source)!=null) return builtins.content(chapter,source)
        val rules = source.json.getJSONObject("ruleContent")
        val pages = mutableListOf<String>()
        var page = chapter.url
        val seen = mutableSetOf<String>()
        repeat(20) {
            if(page.isBlank() || !seen.add(page)) return@repeat
            val html = request(source,page)
            pages += value(html,rules.getString("content"),page)
            val next = value(html,rules.text("nextContentUrl"),page)
            page = if(next.isBlank()) "" else page.toHttpUrl().resolve(next)?.toString().orEmpty()
        }
        val text = pages.joinToString("\n\n").trim()
        require(text.isNotBlank()) { "正文为空，请重试或更换书源" }
        require(text.length <= 1_000_000) { "章节过长" }
        return text
    }
}
