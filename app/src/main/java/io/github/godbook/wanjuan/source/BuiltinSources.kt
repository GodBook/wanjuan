package io.github.godbook.wanjuan.source

import android.content.Context
import io.github.godbook.wanjuan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder

/** Small, explicit catalogue of classic editions. No remote code or source subscriptions. */
class BuiltinSources(private val context: Context) {
    data class Edition(val title: String, val traditional: String, val author: String, val pg: Int? = null)
    private val lock = Mutex()
    private var cachedId: Int? = null
    private var cachedChapters: List<Pair<String, String>> = emptyList()
    private val network = Network()
    private val headers=mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10) WanJuan/0.1.1 (https://github.com/GodBook/wanjuan)")

    fun search(query: String, source: Source): List<Book> = editions.filter {
        (provider(source) != "gutenberg" || it.pg != null) &&
            listOf(it.title, it.traditional, it.author).any { name -> name.contains(query.trim(), true) }
    }.map { edition ->
        Book(title=edition.title, author=edition.author, sourceId=source.id,
            url=if(provider(source)=="gutenberg") "https://www.gutenberg.org/ebooks/${edition.pg}" else wikiUrl(edition.traditional),
            intro="${source.name} · ${edition.traditional}。经典作品的公开版本，正文以来源原文为准。首次打开需联网；可在阅读菜单缓存章节。")
    }

    suspend fun catalog(book: Book, source: Source): Book = withContext(Dispatchers.IO) {
        val chapters = if(provider(source)=="gutenberg") {
            val id=pgId(book.url)
            gutenberg(id).mapIndexed { index, pair -> chapter(source, pair.first, "https://www.gutenberg.org/ebooks/$id#wanjuan-$index") }
        } else {
            require(book.url.startsWith("https://zh.wikisource.org/wiki/"))
            val doc=Jsoup.parse(network.text(book.url,headers),book.url)
            val title=java.net.URLDecoder.decode(book.url.substringAfter("/wiki/"),"UTF-8")
            doc.select(".mw-parser-output a[title]").filter {
                it.attr("title").startsWith("$title/") && Regex("第.*[回卷章]").containsMatchIn(it.attr("title")) && !it.hasClass("new")
            }.map { chapter(source,it.text(),it.absUrl("href")) }.distinctBy { it.url }
        }
        require(chapters.isNotEmpty()) { "此版本目录暂不可用，可尝试另一内置书源" }
        book.copy(chapters=chapters,tocUrl=book.url)
    }

    suspend fun content(chapter: Chapter, source: Source): String = withContext(Dispatchers.IO) {
        val text=if(provider(source)=="gutenberg") {
            val index=chapter.url.substringAfter("#wanjuan-").toInt()
            gutenberg(pgId(chapter.url)).getOrNull(index)?.second ?: error("章节已变化，请刷新目录")
        } else {
            require(chapter.url.startsWith("https://zh.wikisource.org/wiki/"))
            val doc=Jsoup.parse(network.text(chapter.url,headers),chapter.url)
            val body=doc.selectFirst("#mw-content-text .mw-parser-output") ?: error("正文暂不可用")
            body.select(".ws-noexport,.noprint,.mw-editsection,.navbox,.header-container,table,style,script").remove()
            Rules.htmlText(body.html())
        }
        require(text.length in 80..1_000_000) { "正文不完整，请尝试另一来源" }
        text
    }

    private fun pgId(url: String): Int {
        require(url.startsWith("https://www.gutenberg.org/ebooks/"))
        val id=url.substringAfter("/ebooks/").substringBefore('#').toInt()
        require(editions.any { it.pg==id }) { "不在内置版本目录中" }
        return id
    }
    private suspend fun gutenberg(id: Int): List<Pair<String,String>> = lock.withLock {
        if(cachedId==id) return@withLock cachedChapters
        val dir=File(context.cacheDir,"classic-editions").apply { mkdirs() }
        val file=File(dir,"$id.txt")
        val raw=if(file.exists() && System.currentTimeMillis()-file.lastModified()<7*86400000L) file.readText() else {
            network.text("https://www.gutenberg.org/cache/epub/$id/pg$id.txt",headers).also { text ->
                require(text.contains("*** START OF") && text.contains("*** END OF")) { "来源返回了不完整的电子书" }
                val temp=File(dir,"$id.tmp");temp.writeText(text)
                java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val parsed=splitGutenberg(raw)
        require(parsed.isNotEmpty()) { "无法解析此版本章节" }
        cachedId=id;cachedChapters=parsed
        parsed
    }

    companion object {
        val editions=listOf(Edition("红楼梦","紅樓夢","曹雪芹",24264),Edition("西游记","西遊記","吴承恩",23962),Edition("三国演义","三國演義","罗贯中"),Edition("儒林外史","儒林外史","吴敬梓"))
        fun wikiUrl(title: String)="https://zh.wikisource.org/wiki/"+URLEncoder.encode(title,"UTF-8").replace("+","%20")
        private fun source(kind: String,name: String,url: String)=Source("builtin-$kind",JSONObject().put("bookSourceName",name).put("bookSourceUrl",url).put("bookSourceGroup","内置经典").put("wanjuanProvider",kind).toString(),status="内置精选版本 · 可搜索简繁书名 / 作者")
        fun defaults()=listOf(source("wikisource","维基文库 · 中文经典","https://zh.wikisource.org/"),source("gutenberg","古登堡 · 中文经典","https://www.gutenberg.org/"))
        fun provider(source: Source): String? = source.json.optString("wanjuanProvider").takeIf { kind ->
            source.id=="builtin-$kind" && when(kind) { "wikisource" -> source.url=="https://zh.wikisource.org/"; "gutenberg" -> source.url=="https://www.gutenberg.org/"; else -> false }
        }
        suspend fun install(library: Library, restore: Boolean=false) {
            library.mutation.withLock {
                if(!restore && library.dao.get("builtin","installed-v1")!=null) return@withLock
                defaults().forEach { if(library.dao.get("source",it.id)==null) library.dao.put(Record("source",it.id,it.encode())) }
                library.dao.put(Record("builtin","installed-v1","1"))
            }
        }
        private fun chapter(source: Source,title: String,url: String)=Chapter(id=sha256((source.id+url).toByteArray()).take(32),title=title,url=url)
        fun splitGutenberg(raw: String): List<Pair<String,String>> {
            val start=raw.indexOf("*** START OF")
            val end=raw.indexOf("*** END OF")
            require(start>=0 && end>start) { "电子书边界缺失" }
            val body=raw.substring(raw.indexOf('\n',start)+1,end)
            val heading=Regex("^第[零〇○一二三四五六七八九十百千万兩两0-9]+[回章卷](?:[ \t　]+.{0,70})?$")
            val chapters=mutableListOf<Pair<String,String>>()
            val separator=Regex("[-_=]{3,}")
            var title="卷首"
            var started=false
            val text=StringBuilder()
            fun flush() { if(text.isNotBlank()) chapters+=title to text.toString().trim();text.setLength(0) }
            body.lineSequence().forEach { line ->
                val clean=line.trim()
                if(heading.matches(clean)) { if(started) flush() else { text.setLength(0);started=true };title=clean }
                else if(!clean.matches(separator)) {
                    // Gutenberg's hard-wrapped lines are not paragraph boundaries.
                    if(clean.isEmpty()) { if(text.isNotEmpty()) text.append("\n\n") }
                    else { if(line.startsWith("　") && text.isNotEmpty()) text.append("\n\n");text.append(clean) }
                }
            }
            flush();return chapters
        }
    }
}
