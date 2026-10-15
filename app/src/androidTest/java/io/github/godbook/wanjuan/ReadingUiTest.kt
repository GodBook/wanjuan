package io.github.godbook.wanjuan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.godbook.wanjuan.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingUiTest {
    @Test fun readingTurnsPagesAndKeepsProgressAcrossRestart()=runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as WanJuanApp
        val file=File(context.cacheDir,"reading-ui.txt")
        file.writeText("第一章 起点\n"+(1..30).joinToString("\n\n") {"这是第${it}段阅读验收文字。"+"山间的风慢慢吹过书页，窗边的灯光仍然明亮。".repeat(8)}+"\n第二章 归途\n故事继续。")
        val book=BookImporter(app.library).importFile(file,"真机阅读验收.txt")
        app.library.progress(book.id,0,0,"")
        app.library.saveProfile(Profile())
        val device=UiDevice.getInstance(instrumentation)
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        // Shell launch avoids Android 10 background activity-start restrictions in instrumentation.
        device.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")
        assertTrue("书架应出现测试书",device.wait(Until.hasObject(By.text(book.title)),20000))
        device.findObjects(By.text(book.title)).first().click()
        assertTrue("阅读页应显示页码",device.wait(Until.hasObject(By.textContains("页")),10000))
        assertTrue("正文排版完成后再翻页",device.wait(Until.hasObject(By.descContains("这是第1段阅读验收文字")),20000))
        device.click((device.displayWidth*.88).toInt(),device.displayHeight/2)
        assertTrue("右侧点击应进入第二页",device.wait(Until.hasObject(By.textContains("2/")),5000))
        delay(500)
        val progressed=app.library.book(book.id)!!
        assertTrue("文字位置应保存",progressed.offset>0)
        device.click(device.displayWidth/2,device.displayHeight/2)
        assertTrue(device.wait(Until.hasObject(By.desc("添加书签")),3000))
        device.findObject(By.desc("添加书签")).click()
        delay(300)
        assertTrue(app.library.dao.list("mark").any {Mark.decode(it.payload).bookId==book.id})
        device.findObject(By.text("设置")).click()
        assertTrue(device.wait(Until.hasObject(By.text("字号")),3000))
        device.findObject(By.text("完成")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("返回书架")),5000))
        device.findObject(By.desc("返回书架")).click()
        assertTrue(device.wait(Until.hasObject(By.text("万卷")),5000))
        device.findObjects(By.text(book.title)).first().click()
        assertTrue(device.wait(Until.hasObject(By.textContains("2/")),5000))
        // Rapid taps exercise cancellation without retaining or recycling full-screen bitmaps.
        for(mode in listOf(0,1,2)) {
            app.library.saveProfile(Profile(mode=mode))
            delay(400)
            repeat(5) {
                device.click((device.displayWidth*.88).toInt(),device.displayHeight/2)
                delay(70)
                device.click((device.displayWidth*.12).toInt(),device.displayHeight/2)
                delay(70)
            }
            assertTrue("模式 $mode 快速翻页后应回到第二页",device.wait(Until.hasObject(By.textContains("2/")),5000))
        }
        app.library.saveProfile(Profile(mode=3));delay(500)
        val beforeScroll=app.library.book(book.id)!!.offset
        device.swipe(device.displayWidth/2,(device.displayHeight*.7).toInt(),device.displayWidth/2,(device.displayHeight*.3).toInt(),20)
        delay(1000)
        assertTrue("滚动模式应保存向前的正文位置",app.library.book(book.id)!!.offset>beforeScroll)
        app.library.saveProfile(Profile())
        device.pressBack()
        app.library.removeBook(book.id);file.delete()
    }
}
