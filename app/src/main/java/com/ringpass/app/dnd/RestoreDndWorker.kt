package com.ringpass.app.dnd

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ringpass.app.RingPassApplication

// Retained for WorkManager jobs scheduled by previous RingPass versions.
class RestoreDndWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        (applicationContext as RingPassApplication).dndController.cleanUpLegacyOverride()
        return Result.success()
    }
}
