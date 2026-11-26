package com.ringpass.app.dnd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ringpass.app.RingPassApplication
import java.util.concurrent.TimeUnit

class RestoreDndWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RingPassApplication ?: return Result.success()
        val dndController = app.dndController
        if (!dndController.isTemporarilyUnmuted()) {
            return Result.success()
        }

        val tm = applicationContext.getSystemService(TelephonyManager::class.java)
        val hasPhonePerm = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPhonePerm && tm?.callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            val followUp = OneTimeWorkRequestBuilder<RestoreDndWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "ringpass_restore_dnd_safeguard",
                ExistingWorkPolicy.REPLACE,
                followUp
            )
            return Result.success()
        }

        dndController.restoreDndIfNeeded()
        return Result.success()
    }
}
