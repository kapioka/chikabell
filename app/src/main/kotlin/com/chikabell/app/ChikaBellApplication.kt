package com.chikabell.app

import android.app.Application
import com.chikabell.app.geofence.GeofenceRestoreScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChikaBellApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    val container: AppContainer by lazy {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        GeofenceRestoreScheduler.ensurePeriodicHealthCheck(this)
        applicationScope.launch(Dispatchers.IO) {
            container.activityRecognitionRegistrar.registerIfAllowed()
        }
    }
}
