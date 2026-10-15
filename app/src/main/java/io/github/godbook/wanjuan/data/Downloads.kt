package io.github.godbook.wanjuan.data

import android.content.Context
import androidx.work.*
import io.github.godbook.wanjuan.WanJuanApp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadSpec(val bookId: String,val start: Int,val end: Int,val wifi: Boolean,val generation: String=uid(),val paused: Boolean=false) {
    fun encode()=JSONObject().put("book",bookId).put("start",start).put("end",end).put("wifi",wifi).put("generation",generation).put("paused",paused).toString()
    companion object {fun decode(raw: String)=JSONObject(raw).let {DownloadSpec(it.getString("book"),it.getInt("start"),it.getInt("end"),it.getBoolean("wifi"),it.getString("generation"),it.optBoolean("paused"))}}
}

class ChapterWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as WanJuanApp
        val id=inputData.getString("book") ?: return Result.failure()
        val book=app.library.book(id) ?: return Result.failure()
        val source=app.library.source(book.sourceId) ?: return Result.failure()
        val generation=inputData.getString("generation") ?: return Result.failure(workDataOf("error" to "旧版任务，请点击继续沿用原网络设置"))
        if(book.sourceId!=inputData.getString("source") || book.url!=inputData.getString("url")) return Result.failure(workDataOf("error" to "来源已变化，请重新创建下载"))
        val start=inputData.getInt("start",0)
        val end=inputData.getInt("end",book.chapters.lastIndex).coerceAtMost(book.chapters.lastIndex)
        return try {
            for(i in start..end) {
                if(isStopped) return Result.retry()
                val c=book.chapters[i]
                if(c.id!=inputData.getStringArray("chapters")?.getOrNull(i-start)) return Result.failure(workDataOf("error" to "目录已变化，请重新创建下载"))
                val path="cache/${c.id}.txt"
                val cached=app.library.text(path)
                val text=cached ?: app.engine.content(c,source)
                app.library.putChapter(book,c,text,generation)
                setProgress(workDataOf("done" to i-start+1,"total" to end-start+1))
            }
            Result.success()
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(runAttemptCount<2) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "下载失败")))
        }
    }
    companion object {
        suspend fun enqueue(context: Context, book: Book, start: Int = book.chapter, end: Int = book.chapters.lastIndex, wifi: Boolean = true) = withContext(Dispatchers.IO) {
            require(start in book.chapters.indices && end in start..book.chapters.lastIndex) {"下载范围无效"}
            val app=context.applicationContext as WanJuanApp
            val spec=DownloadSpec(book.id,start,end,wifi)
            app.library.put(Record("download",book.id,spec.encode()))
            val requests=(start..end).chunked(20).map { batch ->
                OneTimeWorkRequestBuilder<ChapterWorker>().setInputData(workDataOf("book" to book.id,"start" to batch.first(),"end" to batch.last(),
                    "generation" to spec.generation,"source" to book.sourceId,"url" to book.url,"chapters" to batch.map {book.chapters[it].id}.toTypedArray()))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if(wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("downloads").addTag("book:${book.id}")
                    .addTag("generation:${spec.generation}").addTag("range:$start:$end").addTag("wifi:$wifi").addTag("batch:${batch.size}").build()
            }
            var chain=WorkManager.getInstance(context).beginUniqueWork("download:${book.id}",ExistingWorkPolicy.REPLACE,requests.first())
            requests.drop(1).forEach {chain=chain.then(it)}
            chain.enqueue().result.get()
        }
        suspend fun pause(context: Context,id: String) = withContext(Dispatchers.IO) {
            val library=(context.applicationContext as WanJuanApp).library
            library.dao.get("download",id)?.let {library.put(it.copy(payload=DownloadSpec.decode(it.payload).copy(paused=true).encode()))}
            WorkManager.getInstance(context).cancelUniqueWork("download:$id").result.get()
        }
        suspend fun resume(context: Context,book: Book) {
            val library=(context.applicationContext as WanJuanApp).library
            val spec=library.dao.get("download",book.id)?.let {DownloadSpec.decode(it.payload)} ?: error("任务已失效，请在阅读菜单重新下载")
            enqueue(context,book,spec.start,spec.end,spec.wifi)
        }
        suspend fun cancel(context: Context,id: String) = withContext(Dispatchers.IO) {
            val library=(context.applicationContext as WanJuanApp).library
            library.delete("download",id)
            WorkManager.getInstance(context).cancelUniqueWork("download:$id").result.get()
        }
    }
}
