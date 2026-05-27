package com.marinov.boletosfei

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkerManagerHelper {

    fun iniciarWorkers(context: Context) {
        val appContext = context.applicationContext

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val boletosWork = PeriodicWorkRequest.Builder(
            BoletosWorker::class.java, 20, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val loginWork = PeriodicWorkRequest.Builder(
            LoginWorker::class.java, 15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val updateWork = PeriodicWorkRequest.Builder(
            UpdateCheckWorker::class.java, 120, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val workManager = WorkManager.getInstance(appContext)

        workManager.enqueueUniquePeriodicWork(
            "BoletosWorkerTask",
            ExistingPeriodicWorkPolicy.KEEP,
            boletosWork
        )
        workManager.enqueueUniquePeriodicWork(
            "LoginWorkerTask",
            ExistingPeriodicWorkPolicy.KEEP,
            loginWork
        )
        workManager.enqueueUniquePeriodicWork(
            "UpdateCheckWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            updateWork
        )
    }
}