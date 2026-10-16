package io.github.godbook.wanjuan

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.godbook.wanjuan.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Explicit release-only probes. Default CI does not kill its runner or run a two-hour soak. */
@RunWith(AndroidJUnit4::class)
class AcceptanceProbeTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val library=(context.applicationContext as WanJuanApp).library
    private val args=InstrumentationRegistry.getArguments()

    @Test fun prepareRealProcessDeath()=runBlocking<Unit> {
        val phase=args.getString("restoreDeathPhase")
        Assume.assumeTrue(phase in listOf("journal","old-moved","files-installed","settings-written","committed"))
        val original=Backup(library).create()
        original.copyTo(File(context.filesDir,"restart-original.zip"),true);original.delete()
        val book=Book(title="新书架",group="new",offset=2,anchor="新",chapters=listOf(Chapter(title="第一章",file="books/restart-probe/0.txt")))
        library.save(book);library.putText(book.chapters[0].file,"新状态");library.saveProfile(Profile(size=27f));library.preference("restartToken","new")
        val fresh=Backup(library).create()
        library.putText(book.chapters[0].file,"旧状态");library.saveProfile(Profile(size=18f));library.preference("restartToken","old")
        library.updateBook(book.id) {it.copy(title="旧书架",group="old",offset=1,anchor="旧")}
        File(context.filesDir,"restart-probe.json").writeText(JSONObject().put("phase",phase).put("book",book.id).put("path",book.chapters[0].file).put("backup",fresh.path).toString())
        Backup(library) {at -> if(at==phase) android.os.Process.killProcess(android.os.Process.myPid())}.restore(fresh)
        fail("进程应在指定阶段退出")
    }
    @Test fun verifyRealProcessRestart()=runBlocking<Unit> {
        Assume.assumeTrue(args.getString("verifyDeath")=="true")
        val probe=File(context.filesDir,"restart-probe.json")
        val state=JSONObject(probe.readText())
        val committed=state.getString("phase")=="committed"
        try {
            assertEquals(if(committed) "新状态" else "旧状态",library.text(state.getString("path")))
            assertEquals(if(committed) 27f else 18f,library.profile.first().size,0f)
            assertEquals(if(committed) "new" else "old",library.preferences.first().getString("restartToken"))
            val restored=library.book(state.getString("book"))!!
            assertEquals(if(committed) "新书架" else "旧书架",restored.title)
            assertEquals(if(committed) "new" else "old",restored.group)
            assertEquals(if(committed) 2 else 1,restored.offset)
            assertFalse(File(context.filesDir,"restore-journal.json").exists())
        } finally {
            val original=File(context.filesDir,"restart-original.zip")
            Backup(library).restore(original);original.delete();File(state.getString("backup")).delete();probe.delete()
        }
    }

    @Test fun progressCostWithHundredThousandChapters()=runBlocking<Unit> {
        Assume.assumeTrue(args.getString("performance")=="true")
        val books=(1..100).map {n -> Book(title="大书库 $n",chapters=(1..1000).map {i -> Chapter(id="scale-$n-$i",title="第 $i 章",url="https://example.org/$n/$i")})}
        try {
            books.forEach {library.save(it)}
            val book=books.first()
            val oldPayload=book.encode()
            val positions=mutableListOf<Long>();val legacy=mutableListOf<Long>()
            repeat(31) {i ->
                var start=System.nanoTime();library.put(Record("book",book.id,Book.decode(oldPayload).copy(offset=i,anchor="位置").encode()))
                val oldTime=(System.nanoTime()-start)/1000
                start=System.nanoTime();library.progress(book.id,0,i,"位置",book)
                val newTime=(System.nanoTime()-start)/1000
                if(i>0) {legacy+=oldTime;positions+=newTime}
            }
            val newPayload=library.dao.get("progress",book.id)!!.payload
            assertEquals(30,library.book(book.id)!!.offset)
            val result=JSONObject().put("books",100).put("chaptersPerBook",1000).put("iterations",30)
                .put("legacyPayloadBytes",oldPayload.toByteArray().size).put("progressPayloadBytes",newPayload.toByteArray().size)
                .put("legacyMicros",org.json.JSONArray(legacy)).put("progressMicros",org.json.JSONArray(positions))
                .put("legacyMedianMicros",legacy.sorted()[15]).put("progressMedianMicros",positions.sorted()[15])
            File(context.filesDir,"progress-benchmark.json").writeText(result.toString(2))
            android.util.Log.i("WanJuanAcceptance",result.toString())
        } finally {books.forEach {library.removeBook(it.id)}}
    }

    @Test fun twoHourReadingSoak()=runBlocking<Unit> {
        Assume.assumeTrue(args.getString("soak")=="true")
        val minutes=args.getString("soakMinutes")?.toLongOrNull() ?: 120L
        require(minutes>=120) {"发布稳定性验收至少两小时"}
        val file=File(context.cacheDir,"soak-${uid()}.txt")
        file.writeText((1..8).joinToString("\n") {"第${it}章 长时阅读\n"+"山间有风，书页安静翻过。".repeat(3000)})
        val book=BookImporter(library).importFile(file,"长时阅读验收.txt")
        library.progress(book.id,0,0,"")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val samples=org.json.JSONArray();val started=android.os.SystemClock.elapsedRealtime()
        val output=File(context.filesDir,"soak-result.json")
        try {
            library.saveProfile(Profile(awake=true))
            scenario.onActivity {it.model.open(book.copy(chapter=0,offset=0,anchor=""))}
            assertTrue(device.wait(Until.hasObject(By.descContains("山间有风")),20000))
            var turns=0;var round=0
            while(android.os.SystemClock.elapsedRealtime()-started<minutes*60000) {
                assertEquals("离开万卷后停止测试触控",context.packageName,device.currentPackageName)
                val mode=(round/60)%4
                if(round%60==0) library.saveProfile(Profile(mode=mode,awake=true))
                if(mode==3) device.swipe(device.displayWidth/2,(device.displayHeight*.65).toInt(),device.displayWidth/2,(device.displayHeight*.35).toInt(),12)
                else {
                    device.click((device.displayWidth*.88).toInt(),device.displayHeight/2);delay(250)
                    assertEquals("离开万卷后停止测试触控",context.packageName,device.currentPackageName)
                    device.click((device.displayWidth*.12).toInt(),device.displayHeight/2);turns+=2
                }
                if(round%20==0) {
                    scenario.onActivity {activity -> assertEquals(ReaderLoad.Ready,activity.model.readerLoad.value);activity.model.flush()}
                    val sample=JSONObject().put("elapsedMs",android.os.SystemClock.elapsedRealtime()-started).put("pssKiB",android.os.Debug.getPss()).put("turns",turns).put("mode",mode)
                    samples.put(sample)
                    output.writeText(JSONObject().put("complete",false).put("samples",samples).toString())
                }
                if(round%120==119) {
                    scenario.onActivity {it.model.closeReader()};delay(300)
                    val saved=library.book(book.id)!!;scenario.onActivity {it.model.open(saved)}
                    assertTrue(device.wait(Until.hasObject(By.descContains("山间有风")),20000))
                }
                round+=1;delay(750)
            }
            output.writeText(JSONObject().put("complete",true).put("durationMs",android.os.SystemClock.elapsedRealtime()-started).put("turns",turns).put("samples",samples).toString())
        } finally {scenario.close();library.saveProfile(Profile());library.removeBook(book.id);library.file(book.chapters.first().file).parentFile!!.deleteRecursively();file.delete()}
    }

    @Test fun backupNearSizeLimitAndCancellation()=runBlocking<Unit> {
        Assume.assumeTrue(args.getString("largeBackup")=="true")
        val prefix="assets/limit-${uid()}"
        val files=(1..5).map {library.file("$prefix-$it.bin")}
        val buffer=ByteArray(64*1024);java.util.Random(42).nextBytes(buffer)
        val before=android.os.Debug.getPss();var peak=before
        val monitor=launch(Dispatchers.Default) {while(isActive) {peak=maxOf(peak,android.os.Debug.getPss());delay(250)}}
        var backup: File?=null
        val start=System.nanoTime()
        try {
            files.forEach {file -> file.parentFile!!.mkdirs();file.outputStream().buffered().use {out -> repeat(1600) {out.write(buffer)}}}
            backup=Backup(library).create()
            val expected=files.map {fileHash(it)}
            files.forEach {it.delete()}
            Backup(library).restore(backup)
            assertEquals(expected,files.map {fileHash(it)})
            val tooLarge=library.file("$prefix-over.bin")
            java.io.RandomAccessFile(tooLarge,"rw").use {it.setLength(Backup.MAX_FILE+1)}
            assertTrue("超限必须拒绝导出",runCatching {Backup(library).create()}.isFailure);tooLarge.delete()
            val oldTemps=context.cacheDir.listFiles()!!.map {it.name}.toSet()
            val cancelled=launch(Dispatchers.IO) {Backup(library).create(progress={throw CancellationException("取消验收")})}
            cancelled.join()
            assertTrue(context.cacheDir.listFiles()!!.none {it.name.startsWith("wanjuan-") && it.name !in oldTemps})
            monitor.cancelAndJoin()
            File(context.filesDir,"large-backup-benchmark.json").writeText(JSONObject().put("expandedMiB",500).put("zipBytes",backup.length())
                .put("durationMs",(System.nanoTime()-start)/1_000_000).put("baselinePssKiB",before).put("sampledPeakPssKiB",peak).put("sampleIntervalMs",250).toString(2))
        } finally {monitor.cancelAndJoin();backup?.delete();files.forEach {it.delete()};library.file("$prefix-over.bin").delete()}
    }
}
