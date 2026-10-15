package io.github.godbook.wanjuan.data

import android.content.Context
import androidx.work.*
import io.github.godbook.wanjuan.WanJuanApp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChapterWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as WanJuanApp
        val id=inputData.getString("book") ?: return Result.failure()
        val book=app.library.book(id) ?: return Result.failure()
        val source=app.library.source(book.sourceId) ?: return Result.failure()
        val start=inputData.getInt("start",0)
        val end=inputData.getInt("end",book.chapters.lastIndex).coerceAtMost(book.chapters.lastIndex)
        return try {
            for(i in start..end) {
                if(isStopped) return Result.retry()
                val c=book.chapters[i]
                val path="cache/${c.id}.txt"
                if(!app.library.file(path).exists()) app.library.putText(path,app.engine.content(c,source))
                app.library.markDownloaded(c.id)
                setProgress(workDataOf("done" to i-start+1,"total" to end-start+1))
            }
            Result.success()
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(runAttemptCount<2) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "下载失败")))
        }
    }
    companion object {
        fun enqueue(context: Context, book: Book, start: Int = book.chapter, end: Int = book.chapters.lastIndex, wifi: Boolean = true) {
            val generation=System.currentTimeMillis()
            val requests=(start..end).chunked(20).map { batch ->
                OneTimeWorkRequestBuilder<ChapterWorker>().setInputData(workDataOf("book" to book.id,"start" to batch.first(),"end" to batch.last()))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if(wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("downloads").addTag("book:${book.id}")
                    .addTag("generation:$generation").addTag("range:$start:$end").addTag("batch:${batch.size}").build()
            }
            if(requests.isEmpty()) return
            var chain=WorkManager.getInstance(context).beginUniqueWork("download:${book.id}",ExistingWorkPolicy.KEEP,requests.first())
            requests.drop(1).forEach {chain=chain.then(it)}
            chain.enqueue()
        }
    }
}
