package io.github.godbook.wanjuan.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile
import io.github.godbook.wanjuan.source.Rules

class BookImporter(private val library: Library) {
    suspend fun import(uri: Uri, displayName: String, encoding: String = "自动"): Book = withContext(Dispatchers.IO) {
        val temporary = File(library.context.cacheDir,"import-${uid()}")
        try {
            library.context.contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { out ->
                val buf = ByteArray(8192); var count = 0L
                while(true) { val n = input.read(buf); if(n < 0) break; count += n; require(count <= 100L*1024*1024) { "书籍超过 100MB 限制" }; out.write(buf,0,n) }
            } }
            importFile(temporary,displayName,encoding)
        } finally { temporary.delete() }
    }
    suspend fun importFile(file: File, name: String, encoding: String = "自动"): Book = withContext(Dispatchers.IO) {
        val hash = file.inputStream().use { stream -> val md = java.security.MessageDigest.getInstance("SHA-256"); val buf = ByteArray(8192); while(true) { val n = stream.read(buf); if(n < 0) break; md.update(buf,0,n) }; md.digest().joinToString("") { "%02x".format(it) } }
        library.dao.list("book").map { Book.decode(it.payload) }.find { it.localHash == hash }?.let { return@withContext it }
        val id = uid()
        var book = Book(id=id,title=name.substringBeforeLast('.'),localHash=hash)
        val chapters: List<Chapter>
        if(name.endsWith(".epub",true)) {
            ZipFile(file).use { zip ->
                require(zip.size() < 10000) { "EPUB 文件条目过多" }
                var expanded = 0L
                fun read(path: String): String {
                    val entry = zip.getEntry(path) ?: error("EPUB 缺少文件：$path")
                    require(entry.size <= 16L*1024*1024) { "EPUB 章节过大" }
                    val bytes = zip.getInputStream(entry).use { it.readBytesLimited(16*1024*1024) }
                    expanded += bytes.size; require(expanded <= 100L*1024*1024) { "EPUB 解压内容过大" }
                    return bytes.toString(Charsets.UTF_8)
                }
                require(zip.getEntry("META-INF/encryption.xml") == null) { "首版不支持加密或字体混淆 EPUB" }
                val container = Jsoup.parse(read("META-INF/container.xml"),"",Parser.xmlParser())
                val opfPath = container.selectFirst("rootfile")?.attr("full-path") ?: error("EPUB 缺少书籍清单")
                val opf = Jsoup.parse(read(opfPath),"",Parser.xmlParser())
                val title = opf.getElementsByTag("dc:title").firstOrNull()?.text().orEmpty()
                val author = opf.getElementsByTag("dc:creator").firstOrNull()?.text().orEmpty()
                val base = opfPath.substringBeforeLast('/',"")
                val items = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }
                chapters = opf.select("spine > itemref").mapIndexedNotNull { index, ref ->
                    val href = items[ref.attr("idref")] ?: return@mapIndexedNotNull null
                    val path = java.net.URI("/" + (if(base.isBlank()) "" else "$base/") + href).normalize().path.removePrefix("/")
                    require(!path.startsWith("../")) { "非法 EPUB 路径" }
                    val html = read(path)
                    val doc = Jsoup.parse(html)
                    doc.select("img").forEach { image ->
                        val src=image.attr("src")
                        if(src.isNotBlank() && !src.contains(":") && !src.startsWith("//")) {
                            val imagePath=java.net.URI("/"+path).resolve(src).normalize().path.removePrefix("/")
                            val entry=zip.getEntry(imagePath)
                            if(entry!=null) {
                                val imageBytes=zip.getInputStream(entry).use {it.readBytesLimited(8*1024*1024)}
                                expanded+=imageBytes.size;require(expanded<=100L*1024*1024) {"EPUB 解压内容过大"}
                                val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                                android.graphics.BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.size,options)
                                if(options.outWidth>0 && options.outWidth.toLong()*options.outHeight<=40_000_000) {
                                    val relative="books/$id/images/${sha256(imagePath.toByteArray()).take(24)}.img"
                                    library.file(relative).apply {parentFile?.mkdirs();writeBytes(imageBytes)}
                                    image.after("<p>[[wanjuan-image:$relative]]</p>")
                                }
                            }
                        }
                        image.remove()
                    }
                    val text = Rules.htmlText(doc.outerHtml())
                    if(text.isBlank()) null else {
                        val chapterTitle = doc.selectFirst("h1,h2,h3,title")?.text()?.takeIf { it.isNotBlank() } ?: "第 ${index+1} 节"
                        val relative = "books/$id/$index.txt"
                        library.putText(relative,text)
                        Chapter(title=chapterTitle,file=relative)
                    }
                }
                book = book.copy(title=title.ifBlank { book.title },author=author)
            }
        } else {
            require(name.endsWith(".txt",true)) { "请选择 TXT 或 EPUB 文件" }
            val bytes = file.inputStream().use { input -> val head=ByteArray(65536);val n=input.read(head);head.copyOf(n.coerceAtLeast(0)) }
            val charset = if(encoding != "自动") java.nio.charset.Charset.forName(encoding) else when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                else -> if(runCatching {
                    val result=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes),java.nio.CharBuffer.allocate(bytes.size),file.length()<=bytes.size)
                    !result.isError
                }.getOrDefault(false)) Charsets.UTF_8 else java.nio.charset.Charset.forName("GB18030")
            }
            val parsed=mutableListOf<Chapter>()
            val heading=Regex("^[ \\t　]*(第[零〇一二三四五六七八九十百千万两0-9]+[章节卷回部篇][^\\n]{0,45}|(?:序章|楔子|前言|后记|尾声)[^\\n]{0,30})[ \\t]*$")
            var title="卷首"
            var hasHeadings=false
            val chapterText=StringBuilder()
            suspend fun flush() {
                if(chapterText.isBlank()) return
                val relative="books/$id/${parsed.size}.txt"
                library.putText(relative,chapterText.toString().trim())
                parsed+=Chapter(title=if(hasHeadings) title else "第 ${parsed.size+1} 节",file=relative)
                chapterText.setLength(0)
            }
            suspend fun line(value: String) {
                val clean=value.removePrefix("\uFEFF").trimEnd('\r')
                if(heading.matches(clean)) {
                    flush();hasHeadings=true;title=clean.trim()
                } else {
                    chapterText.append(clean).append('\n')
                    if(chapterText.length >= if(hasHeadings) 200000 else 12000) {flush();if(hasHeadings) title+="（续）"}
                }
            }
            file.reader(charset).buffered().use { reader ->
                val buffer=CharArray(8192);val pending=StringBuilder()
                while(true) {
                    val n=reader.read(buffer);if(n<0) break
                    for(i in 0 until n) {
                        val c=buffer[i]
                        if(c=='\n' || pending.length>=24000) {line(pending.toString());pending.setLength(0);if(c!='\n') pending.append(c)} else pending.append(c)
                    }
                }
                if(pending.isNotEmpty()) line(pending.toString())
            }
            flush()
            chapters=parsed
        }
        require(chapters.isNotEmpty()) { "未找到可阅读的文字内容" }
        val original = library.file("books/$id/original.${if(name.endsWith(".epub",true)) "epub" else "txt"}")
        original.parentFile?.mkdirs(); file.copyTo(original,true)
        book.copy(chapters=chapters).also { library.save(it) }
    }
    companion object {
        fun splitText(text: String): List<Pair<String,String>> {
            val heading = Regex("(?m)^[ \\t　]*(第[零〇一二三四五六七八九十百千万两0-9]+[章节卷回部篇][^\\n]{0,45}|(?:序章|楔子|前言|后记|尾声)[^\\n]{0,30})[ \\t]*$")
            val matches = heading.findAll(text).toList()
            if(matches.isEmpty()) return text.chunked(12000).mapIndexed { i,s -> "第 ${i+1} 节" to s }
            val result = mutableListOf<Pair<String,String>>()
            if(matches.first().range.first > 0 && text.substring(0,matches.first().range.first).isNotBlank()) result += "卷首" to text.substring(0,matches.first().range.first).trim()
            matches.forEachIndexed { i,m -> result += m.value.trim() to text.substring(m.range.last+1,matches.getOrNull(i+1)?.range?.first ?: text.length).trim().ifBlank { m.value.trim() } }
            return result
        }
    }
}
fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while(true) { val n=read(buffer); if(n<0) break; require(out.size().toLong()+n <= limit) { "文件超过大小限制" }; out.write(buffer,0,n) }
    return out.toByteArray()
}
