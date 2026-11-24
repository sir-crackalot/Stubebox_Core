package de.stubebox.tv.core.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.stubebox.tv.core.sync.local.LocalAutoSyncRunner

/**
 * The phone's hourly check ([LocalAutoSyncScheduler]): runs the due agreements, if any. A device that
 * was not there is the agreement's backoff to handle, not WorkManager's — the job itself succeeds.
 */
class LocalAutoSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: LocalAutoSyncRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runner.runDue("hourly")
        return Result.success()
    }
}
