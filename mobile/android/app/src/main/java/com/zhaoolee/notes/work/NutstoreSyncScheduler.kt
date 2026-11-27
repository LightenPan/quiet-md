package com.zhaoolee.notes.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zhaoolee.notes.sync.InitialPolicy
import com.zhaoolee.notes.sync.NutstoreSyncController
import com.zhaoolee.notes.sync.SyncOutcome
import com.zhaoolee.notes.sync.WebDavErrorKind
import com.zhaoolee.notes.sync.WebDavException
import java.util.concurrent.TimeUnit

object NutstoreSyncScheduler {
    private const val WORK_NAME = "quiet-md-nutstore-periodic"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NutstoreSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun ensure(context: Context) {
        val controller = NutstoreSyncController(context.applicationContext)
        val connection = controller.currentConnection() ?: return
        val saved = runCatching { controller.hasPassword(connection) }.getOrDefault(false)
        if (!saved) return
        val request = PeriodicWorkRequestBuilder<NutstoreSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}

class NutstoreSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val controller = NutstoreSyncController(applicationContext)
        val connection = controller.currentConnection() ?: return ListenableWorker.Result.success()
        if (!runCatching { controller.hasPassword(connection) }.getOrDefault(false)) {
            return ListenableWorker.Result.success()
        }
        return try {
            when (controller.sync(InitialPolicy.ASK)) {
                is SyncOutcome.NeedsInitialChoice,
                is SyncOutcome.Synced -> ListenableWorker.Result.success()
            }
        } catch (e: WebDavException) {
            when (e.kind) {
                WebDavErrorKind.AUTH,
                WebDavErrorKind.NOT_FOUND -> ListenableWorker.Result.failure()
                else -> ListenableWorker.Result.retry()
            }
        } catch (_: Exception) {
            ListenableWorker.Result.retry()
        }
    }
}
