package com.chikabell.app

import android.app.Application
import com.chikabell.app.geofence.GeofenceRestoreScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class ChikaBellApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    val container: AppContainer by lazy {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        GeofenceRestoreScheduler.ensurePeriodicHealthCheck(this)
    }
}
