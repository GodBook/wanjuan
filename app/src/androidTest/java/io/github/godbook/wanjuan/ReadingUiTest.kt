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
        val intent=context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue("书架应出现测试书",device.wait(Until.hasObject(By.text(book.title)),10000))
        device.findObjects(By.text(book.title)).first().click()
        assertTrue("阅读页应显示页码",device.wait(Until.hasObject(By.textContains("页")),10000))
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
        device.pressBack()
        app.library.removeBook(book.id);file.delete()
    }
}
