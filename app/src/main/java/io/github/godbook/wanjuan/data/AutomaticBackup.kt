package io.github.godbook.wanjuan.data

import android.content.Context
import androidx.work.*
import io.github.godbook.wanjuan.WanJuanApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AutomaticBackup(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as WanJuanApp
        val dav=WebDav(applicationContext)
        if(dav.load()==null) return Result.failure()
        return try {
            val file=Backup(app.library).create(includeOffline=app.library.preferences.first().optString("backupOffline","true")!="false")
            try {dav.upload(file);dav.retainLatest(10)} finally {file.delete()}
            app.library.backupResult()
            Result.success()
        } catch(e: CancellationException) {throw e}
        catch(e: Exception) {app.library.backupResult(e.message ?: "备份失败");if(runAttemptCount<3) Result.retry() else Result.failure()}
    }
    companion object {
        fun schedule(context: Context,enabled: Boolean) {
            val manager=WorkManager.getInstance(context)
            if(!enabled) manager.cancelUniqueWork("daily-backup")
            else manager.enqueueUniquePeriodicWork("daily-backup",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutomaticBackup>(24,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
