package io.github.godbook.wanjuan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.godbook.wanjuan.data.*
import io.github.godbook.wanjuan.source.*
import io.github.godbook.wanjuan.update.Updater
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val app=context.applicationContext as WanJuanApp
    @Test fun sourceSearchCatalogContentOnDevice()=runBlocking<Unit> {
        val server=MockWebServer();server.start()
        try {
            val base=server.url("/").toString()
            val raw="""{"bookSourceName":"设备测试源","bookSourceUrl":"$base","searchUrl":"search?q={{key}}","ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href","author":"span@text"},"ruleBookInfo":{"tocUrl":".toc@href"},"ruleToc":{"chapterList":".chapter","chapterName":"a@text","chapterUrl":"a@href"},"ruleContent":{"content":"main@html"}}"""
            val source=app.engine.inspect(raw).single().source.copy(http=true)
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/book'>万卷测试</a><span>测试作者</span></div>"))
            val result=app.engine.searchOne("测试",source).single()
            assertEquals("万卷测试",result.title)
            server.enqueue(MockResponse().setBody("<a class='toc' href='/toc'>目录</a>"))
            server.enqueue(MockResponse().setBody("<div class='chapter'><a href='/chapter'>第一章</a></div>"))
            val book=app.engine.catalog(result,source)
            assertEquals(1,book.chapters.size)
            server.enqueue(MockResponse().setBody("<main><p>正文第一段。</p><p>正文第二段。</p></main>"))
            assertEquals("正文第一段。\n\n正文第二段。",app.engine.content(book.chapters[0],source))
        } finally {server.shutdown()}
    }
    @Test fun txtImportProgressAndBackupRoundTrip()=runBlocking<Unit> {
        val file=File(context.cacheDir,"device-sample.txt")
        file.writeText("第一章 启程\n"+"这是阅读测试正文。".repeat(100)+"\n第二章 归来\n阅读位置应当保留。")
        val b=BookImporter(app.library).importFile(file,"设备测试.txt")
        assertEquals(2,b.chapters.size)
        app.library.progress(b.id,1,3,"位置")
        val backup=Backup(app.library).create()
        try {
            assertTrue(Backup(app.library).inspect(backup).contains("书籍"))
            app.library.removeBook(b.id)
            Backup(app.library).restore(backup)
            val restored=app.library.book(b.id)!!
            assertEquals(1,restored.chapter);assertEquals(3,restored.offset)
            assertTrue(app.library.text(restored.chapters[1].file)!!.contains("阅读位置"))
        } finally {backup.delete();file.delete();app.library.removeBook(b.id)}
    }
    @Test fun epubImportUsesSpineOrder()=runBlocking<Unit> {
        val file=File(context.cacheDir,"test.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path:String,value:String) {zip.putNextEntry(ZipEntry(path));zip.write(value.toByteArray());zip.closeEntry()}
            add("META-INF/container.xml","<container><rootfiles><rootfile full-path='OEBPS/book.opf'/></rootfiles></container>")
            add("OEBPS/book.opf","<package><metadata><dc:title>EPUB测试</dc:title><dc:creator>测试作者</dc:creator></metadata><manifest><item id='c1' href='1.xhtml'/><item id='c2' href='2.xhtml'/></manifest><spine><itemref idref='c2'/><itemref idref='c1'/></spine></package>")
            add("OEBPS/1.xhtml","<html><body><h1>第一节</h1><p>甲。</p></body></html>")
            add("OEBPS/2.xhtml","<html><body><h1>第二节</h1><p>乙。</p></body></html>")
        }
        val book=BookImporter(app.library).importFile(file,"test.epub")
        assertEquals("EPUB测试",book.title);assertEquals("第二节",book.chapters.first().title)
        app.library.removeBook(book.id);file.delete()
    }
    @Test fun scriptIsIsolatedAndHasExecutionBudget()=runBlocking<Unit> {
        val scripts=Scripts(context)
        assertEquals("HELLO",scripts.execute("result.toUpperCase()","hello"))
        assertEquals("undefined",scripts.execute("typeof java", ""))
        assertTrue(runCatching {scripts.execute("while(true) {}","")}.isFailure)
    }
    @Test fun rejectsTraversalAndUntrustedUpdateUrls()=runBlocking<Unit> {
        assertTrue(runCatching {app.library.file("../outside.txt")}.isFailure)
        val updater=Updater(context)
        val digest="a".repeat(64)
        assertTrue(runCatching {updater.parseManifest("""{"packageName":"io.github.godbook.wanjuan","versionCode":200,"versionName":"0.2.0","apkUrl":"https://evil.example/app.apk","sha256":"$digest"}""",100)}.isFailure)
        assertNull(updater.parseManifest("""{"packageName":"io.github.godbook.wanjuan","versionCode":100}""",100))
    }
    @Test fun corruptBackupCannotReplaceExistingLibrary()=runBlocking<Unit> {
        val b=Book(title="备份安全验证")
        app.library.save(b)
        val corrupt=File(context.cacheDir,"corrupt.zip")
        ZipOutputStream(corrupt.outputStream()).use {zip ->
            zip.putNextEntry(ZipEntry("state.json"));zip.write("""{"version":1,"records":[],"profile":{}}""".toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write("""{"state.json":"wrong"}""".toByteArray());zip.closeEntry()
        }
        try {assertTrue(runCatching {Backup(app.library).restore(corrupt)}.isFailure);assertNotNull(app.library.book(b.id))}
        finally {app.library.removeBook(b.id);corrupt.delete()}
    }
    @Test fun largeUtf8ImportDoesNotMisdetectPartialProbeCharacter()=runBlocking<Unit> {
        val file=File(context.cacheDir,"large-utf8.txt")
        val text="汉字阅读".repeat(10000)
        file.writeText(text)
        val book=BookImporter(app.library).importFile(file,"大文件验证.txt")
        try {
            val joined=book.chapters.joinToString("") {app.library.file(it.file).readText().replace("\n","")}
            assertEquals(text,joined)
        } finally {app.library.removeBook(book.id);file.delete()}
    }
}
