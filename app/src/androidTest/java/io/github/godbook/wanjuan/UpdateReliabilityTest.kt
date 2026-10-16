package io.github.godbook.wanjuan

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.NetworkType
import io.github.godbook.wanjuan.data.*
import io.github.godbook.wanjuan.source.Network
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class UpdateReliabilityTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val app=context.applicationContext as WanJuanApp
    private val library=app.library
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private suspend fun until(block: suspend ()->Boolean) = withTimeout(20000) {while(!block()) delay(50)}

    @Test fun offlineBackupRestoresProtectedChaptersAndCanExcludeThem()=runBlocking<Unit> {
        val chapter=Chapter(title="离线章",url="https://example.org/chapter")
        val book=Book(title="离线备份验收",sourceId="offline-test",url="https://example.org/book",chapters=listOf(chapter))
        library.save(book);library.putText("cache/${chapter.id}.txt","断网仍可阅读的正文");library.markDownloaded(chapter.id)
        val full=Backup(library).create();val small=Backup(library).create(includeOffline=false)
        try {
            assertTrue(Backup(library).inspect(full).contains("包含 1 章已下载正文"))
            assertTrue(Backup(library).inspect(small).contains("需要联网"))
            library.file("cache/${chapter.id}.txt").delete();library.delete("offline",chapter.id)
            Backup(library).restore(full);library.trimCache(0)
            assertEquals("断网仍可阅读的正文",library.text("cache/${chapter.id}.txt"))
            assertTrue(library.dao.contains("offline",chapter.id))
            assertNull("来源不存在时也必须直接读取离线正文",library.source(book.sourceId))
            device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
            val restored=library.book(book.id)!!
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity {it.model.open(restored)}
                assertTrue("恢复后的在线正文应实际显示",device.wait(Until.hasObject(By.descContains("断网仍可阅读的正文")),15000))
            } finally {scenario.close()}
            Backup(library).restore(small)
            assertNull(library.text("cache/${chapter.id}.txt"));assertFalse(library.dao.contains("offline",chapter.id))
        } finally {full.delete();small.delete();library.removeBook(book.id);library.delete("offline",chapter.id);library.file("cache/${chapter.id}.txt").delete()}
    }

    @Test fun oldFormatBackupAndLegacyProgressRemainReadable()=runBlocking<Unit> {
        val book=Book(title="旧数据样本",chapters=listOf(Chapter(title="第一章",file="books/legacy-test/0.txt")),chapter=0,offset=17,anchor="旧位置")
        library.putText(book.chapters[0].file,"旧位置".repeat(100));library.put(Record("book",book.id,book.encode()))
        assertEquals(17,library.book(book.id)!!.offset)
        library.progress(book.id,0,31,"新位置")
        assertEquals(31,library.book(book.id)!!.offset)
        assertEquals(book.encode(),library.dao.get("book",book.id)!!.payload)
        val state=JSONObject().put("version",1).put("profile",Profile().json()).put("preferences",JSONObject())
            .put("records",JSONArray().put(JSONObject().put("kind","book").put("id",book.id).put("payload",book.encode())))
        val file=File(context.cacheDir,"legacy-${uid()}.zip")
        val manifest=JSONObject()
        ZipOutputStream(file.outputStream()).use {zip ->
            fun add(name: String,bytes: ByteArray) {zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry();manifest.put(name,sha256(bytes))}
            add("state.json",state.toString().toByteArray());add("library/${book.chapters[0].file}","旧位置".repeat(100).toByteArray())
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
        }
        val original=Backup(library).create()
        try {
            Backup(library).restore(file)
            assertEquals(17,library.book(book.id)!!.offset);assertTrue(library.text(book.chapters[0].file)!!.contains("旧位置"))
        } finally {Backup(library).restore(original);original.delete();file.delete();library.removeBook(book.id);library.file(book.chapters[0].file).parentFile!!.deleteRecursively()}
    }

    private class SimulatedDeath: Error()
    @Test fun restoreJournalRecoversFiveInterruptedPhases()=runBlocking<Unit> {
        val original=Backup(library).create()
        val book=Book(title="新恢复书架",group="new",offset=2,anchor="新",chapters=listOf(Chapter(title="第一章",file="books/crash-test/0.txt")))
        library.save(book);library.putText(book.chapters[0].file,"新书库正文");library.saveProfile(Profile(size=28f));library.preference("crashToken","new")
        val fresh=Backup(library).create()
        try {
            for(phase in listOf("journal","old-moved","files-installed","settings-written","committed")) {
                library.putText(book.chapters[0].file,"旧书库正文");library.saveProfile(Profile(size=19f));library.preference("crashToken","old")
                library.updateBook(book.id) {it.copy(title="旧恢复书架",group="old",offset=1,anchor="旧")}
                try {Backup(library) {if(it==phase) throw SimulatedDeath()}.restore(fresh);fail("故障未注入 $phase")}
                catch(_: SimulatedDeath) {}
                library.recoverRestore()
                val committed=phase=="committed"
                assertEquals(phase,if(committed) "新书库正文" else "旧书库正文",library.text(book.chapters[0].file))
                assertEquals(phase,if(committed) 28f else 19f,library.profile.first().size,0f)
                assertEquals(phase,if(committed) "new" else "old",library.preferences.first().getString("crashToken"))
                assertFalse(File(context.filesDir,"restore-journal.json").exists())
                val restored=library.book(book.id)!!
                assertEquals(phase,if(committed) "新恢复书架" else "旧恢复书架",restored.title)
                assertEquals(phase,if(committed) "new" else "old",restored.group)
                assertEquals(phase,if(committed) 2 else 1,restored.offset)
            }
        } finally {library.recoverRestore();Backup(library).restore(original);original.delete();fresh.delete()}
    }

    @Test fun switchingAndRefreshingProtectNotesAndUserState()=runBlocking<Unit> {
        val first=Chapter(title="第一章",url="https://example.org/1")
        val second=Chapter(title="第二章",url="https://example.org/2")
        val old=Book(title="换源验收",sourceId="old",url="https://example.org/book",chapters=listOf(first,second),chapter=1,offset=12,anchor="位置",group="收藏",pinned=true,finished=true,profile="{\"size\":24}")
        val mark=Mark(bookId=old.id,chapter=1,offset=12,quote="摘录",note="不能丢失的笔记") // legacy note
        library.save(old);library.save(mark)
        try {
            val refreshed=library.replaceEdition(old.id,old.copy(chapters=listOf(Chapter(title="序章"),first,second)))
            assertEquals(2,refreshed.chapter)
            var note=Mark.decode(library.dao.get("mark",mark.id)!!.payload)
            assertEquals(2,note.indexIn(refreshed));assertEquals("第二章",note.chapterTitle)
            val candidate=Book(title=old.title,sourceId="new",url="https://another.example/book",chapters=listOf(Chapter(title="合并章")))
            val switched=library.replaceEdition(old.id,candidate,0)
            note=Mark.decode(library.dao.get("mark",mark.id)!!.payload)
            assertTrue(switched.finished);assertTrue(switched.pinned);assertEquals("收藏",switched.group);assertEquals(old.profile,switched.profile)
            assertNull(note.indexIn(switched));assertTrue(note.needsReview);assertEquals(mark.note,note.note);assertEquals("第二章",note.chapterTitle)
            library.progress(old.id,old.chapter,900,"旧请求",old)
            assertEquals("旧版本异步进度不得覆盖新来源",switched.offset,library.book(old.id)!!.offset)
        } finally {library.removeBook(old.id)}
    }

    @Test fun pausedDownloadPreservesMobilePolicyAndSkipsCachedChapter()=runBlocking<Unit> {
        val server=MockWebServer();server.start()
        val source=Source(id=uid(),http=true,raw=JSONObject().put("bookSourceName","下载回归").put("bookSourceUrl",server.url("/")).put("ruleContent",JSONObject().put("content","main@text")).toString())
        val book=Book(title="下载回归",sourceId=source.id,url=server.url("/book").toString(),chapters=(1..2).map {Chapter(title="第 $it 章",url=server.url("/$it").toString())})
        library.save(source);library.save(book)
        try {
            server.enqueue(MockResponse().setBody("<main>第一章离线正文</main>"))
            server.enqueue(MockResponse().setBody("<main>第二章慢响应</main>").setBodyDelay(5,TimeUnit.SECONDS))
            ChapterWorker.enqueue(context,book,0,1,wifi=false)
            until {library.dao.contains("offline",book.chapters[0].id)}
            ChapterWorker.pause(context,book.id)
            val paused=DownloadSpec.decode(library.dao.get("download",book.id)!!.payload)
            assertFalse(paused.wifi);assertTrue(paused.paused)
            server.enqueue(MockResponse().setBody("<main>第二章离线正文</main>"))
            ChapterWorker.resume(context,book)
            val resumed=DownloadSpec.decode(library.dao.get("download",book.id)!!.payload)
            assertFalse(resumed.wifi);assertFalse(resumed.paused);assertNotEquals(paused.generation,resumed.generation)
            val manager=WorkManager.getInstance(context)
            until {manager.getWorkInfosForUniqueWork("download:${book.id}").get().all {it.state==WorkInfo.State.SUCCEEDED}}
            assertTrue(manager.getWorkInfosForUniqueWork("download:${book.id}").get().all {it.constraints.requiredNetworkType==NetworkType.CONNECTED})
            assertEquals("第一章离线正文",library.text("cache/${book.chapters[0].id}.txt"))
            assertTrue(library.dao.contains("offline",book.chapters[1].id))
            assertTrue("缓存章不得重复请求",server.requestCount<=3)
        } finally {ChapterWorker.cancel(context,book.id);library.removeBook(book.id);library.delete("source",source.id);book.chapters.forEach {library.delete("offline",it.id);library.file("cache/${it.id}.txt").delete()};server.shutdown()}
    }

    @Test fun storageRechecksSharedPresetReferencesBeforeDeleting()=runBlocking<Unit> {
        val asset="assets/storage-${uid()}.ttf";library.putText(asset,"资源样本")
        val preset=uid();library.put(Record("preset",preset,JSONObject().put("font",library.file(asset).path).toString()))
        val storage=Storage(library)
        try {
            assertFalse(storage.scan().single {it.path==asset}.removable)
            assertTrue(runCatching {storage.delete(setOf(asset))}.isFailure)
            library.delete("preset",preset)
            assertTrue(storage.scan().single {it.path==asset}.removable)
            library.saveProfile(library.profile.first().copy(font=library.file(asset).path))
            assertTrue("扫描后新增引用也应保护",runCatching {storage.delete(setOf(asset))}.isFailure)
            library.saveProfile(library.profile.first().copy(font=""))
            assertTrue(storage.delete(setOf(asset))>0);assertFalse(library.file(asset).exists())
        } finally {library.delete("preset",preset);library.saveProfile(library.profile.first().copy(font=""));library.file(asset).delete()}
    }

    @Test fun repeatedHistoryAndBackupFeedbackStayCurrent()=runBlocking<Unit> {
        library.clearHistory()
        repeat(15) {library.rememberSearch("书名$it")}
        library.rememberSearch("书名3")
        assertEquals(12,library.history.first().size);assertEquals("书名3",library.history.first().first().id)
        library.backupResult("测试失败");assertEquals("测试失败",library.preferences.first().getString("backupError"))
        library.backupResult();assertFalse(library.preferences.first().has("backupError"));assertTrue(library.preferences.first().getLong("lastBackupAttempt")>0)
        library.clearHistory()
    }

    @Test fun readerFailureIsPersistentAndRapidSearchUsesLatestRequest()=runBlocking<Unit> {
        val book=Book(title="读取失败回归",chapters=listOf(Chapter(title="缺失章",file="books/missing-${uid()}/0.txt")))
        library.save(book)
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {it.model.open(book)}
            assertTrue(device.wait(Until.hasObject(By.text("读取失败")),15000))
            assertTrue(device.hasObject(By.textContains("本地书籍文件缺失")))
            delay(1500);assertTrue(device.hasObject(By.text("读取失败")))
            device.findObject(By.text("重试")).click()
            assertTrue(device.wait(Until.hasObject(By.text("读取失败")),5000))
            scenario.onActivity {it.model.closeReader();it.model.search("红楼梦");it.model.search("西游记")}
            until {var finished=false;scenario.onActivity {finished=!it.model.searching.value && it.model.searchDone.value>=2};finished}
            scenario.onActivity {activity -> assertTrue(activity.model.results.value.all {it.title=="西游记"})}
            val shortGate=CompletableDeferred<Unit>();val longGate=CompletableDeferred<Unit>()
            lateinit var shortJob: Job;lateinit var longJob: Job
            scenario.onActivity {shortJob=it.model.run {shortGate.await()};longJob=it.model.run {longGate.await()}}
            shortGate.complete(Unit);shortJob.join()
            scenario.onActivity {assertTrue("长操作尚未完成",it.model.busy.value)}
            longGate.complete(Unit);longJob.join()
            until {var idle=false;scenario.onActivity {idle=!it.model.busy.value};idle}
        } finally {scenario.close();library.removeBook(book.id)}
    }

    @Test fun streamingTransferBoundsAndLargeBackupRoundTrip()=runBlocking<Unit> {
        val large=library.file("assets/stream-${uid()}.bin");large.parentFile!!.mkdirs()
        val buffer=ByteArray(64*1024) {((it*31+7)%251).toByte()}
        large.outputStream().buffered().use {out -> repeat(1600) {out.write(buffer)}} // 100 MiB, streaming fixture
        val started=System.nanoTime();val before=android.os.Debug.getPss()
        val backup=Backup(library).create()
        try {
            val hash=fileHash(large);large.delete();Backup(library).restore(backup)
            assertEquals(hash,fileHash(large))
            val server=MockWebServer();server.start()
            try {
                server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(128*1024))))
                val output=File(context.cacheDir,"limited-${uid()}.bin")
                assertTrue(runCatching {Network(http=true).download(server.url("/large").toString(),output,limit=64*1024)}.isFailure)
                assertFalse(output.exists());assertFalse(File(output.path+".partial").exists())
            } finally {server.shutdown()}
            android.util.Log.i("WanJuanAcceptance","100MiB backup roundtrip ms=${(System.nanoTime()-started)/1_000_000} PSS-before-KiB=$before PSS-after-KiB=${android.os.Debug.getPss()}")
        } finally {backup.delete();large.delete()}
    }

    @Test fun streamedUploadAndDownloadPreserveBytesAndDigest()=runBlocking<Unit> {
        val server=MockWebServer();server.start()
        val input=File(context.cacheDir,"upload-${uid()}.zip");val output=File(context.cacheDir,"download-${uid()}.zip")
        val bytes=ByteArray(64*1024) {((it*37+11)%251).toByte()}
        try {
            input.outputStream().use {out -> repeat(128) {out.write(bytes)}}
            server.enqueue(MockResponse().setResponseCode(201))
            val network=Network(http=true)
            network.upload(server.url("/backup.zip").toString(),input,mapOf("Authorization" to "Basic test"))
            val request=server.takeRequest(5,TimeUnit.SECONDS)!!
            assertEquals("PUT",request.method);assertEquals("Basic test",request.getHeader("Authorization"));assertEquals(input.length(),request.bodySize)
            assertEquals(fileHash(input),request.body.sha256().hex())
            // MockWebServer retains uploads in memory. Release the verified 8 MiB fixture
            // before constructing the response, including on the API 29 image's 16 MiB heap.
            request.body.clear()
            server.enqueue(MockResponse().setBody(okio.Buffer().apply {repeat(128) {write(bytes)}}))
            val hash=network.download(server.url("/backup.zip").toString(),output)
            assertEquals(fileHash(input),hash);assertEquals(hash,fileHash(output));assertEquals(input.length(),output.length())
        } finally {server.shutdown();input.delete();output.delete()}
    }

    @Test fun storageShowsBookTitlesAndOffersPathDetails()=runBlocking<Unit> {
        val chapter=Chapter(title="第一章 溪流",file="books/storage-ui-${uid()}/0.txt")
        val book=Book(title="存储标题验收",chapters=listOf(chapter))
        library.putText(chapter.file,"存储界面验证正文");library.save(book)
        assertFalse(Storage(library).scan().single {it.path==chapter.file}.removable)
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.text("设置")),10000));device.findObject(By.text("设置")).click()
            assertTrue(device.wait(Until.hasObject(By.text("书源管理")),10000))
            val storageEntry=By.textContains("存储管理")
            repeat(8) {
                if(!device.hasObject(storageEntry)) {
                    assertEquals(context.packageName,device.currentPackageName)
                    device.swipe(device.displayWidth/2,(device.displayHeight*.75).toInt(),device.displayWidth/2,(device.displayHeight*.35).toInt(),25)
                    device.waitForIdle()
                    device.wait(Until.hasObject(storageEntry),1000)
                }
            }
            val storageVisible=device.wait(Until.hasObject(storageEntry),5000)
            if(!storageVisible) {
                val dump=File(context.cacheDir,"storage-settings-failure.xml");device.dumpWindowHierarchy(dump)
                dump.readText().chunked(3000).forEach {android.util.Log.e("WanJuanAcceptance",it)}
            }
            assertTrue("设置中应找到存储管理入口",storageVisible);device.waitForIdle();device.findObject(storageEntry).click()
            val title="${book.title} · ${chapter.title}"
            val categoryVisible=device.wait(Until.hasObject(By.desc("展开本地书籍")),10000)
            if(!categoryVisible) {
                val dump=File(context.cacheDir,"storage-category-failure.xml");device.dumpWindowHierarchy(dump)
                dump.readText().chunked(3000).forEach {android.util.Log.e("WanJuanAcceptance",it)}
            }
            assertTrue("存储页应显示本地书籍分类",categoryVisible);device.findObject(By.desc("展开本地书籍")).click()
            if(!device.hasObject(By.textContains(title))) device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN,Until.findObject(By.textContains(title)))
            val visible=device.wait(Until.hasObject(By.textContains(title)),15000)
            if(!visible) device.dumpWindowHierarchy(File(context.cacheDir,"storage-ui-failure.xml"))
            assertTrue("以书名和章节识别文件",visible)
            assertFalse("路径应按需展开",device.hasObject(By.textContains(chapter.file)))
            val label=device.findObject(By.textContains(title))
            val info=device.findObjects(By.desc("查看文件路径")).minBy {kotlin.math.abs(it.visibleCenter.y-label.visibleCenter.y)}
            info.click()
            assertTrue(device.wait(Until.hasObject(By.textContains(chapter.file)),5000))
        } finally {scenario.close();library.removeBook(book.id);library.file(chapter.file).parentFile!!.deleteRecursively()}
    }
}
