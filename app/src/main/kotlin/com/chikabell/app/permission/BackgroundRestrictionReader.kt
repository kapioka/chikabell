package com.chikabell.app.permission

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager

data class BackgroundRestrictionSnapshot(
    val backgroundRestricted: Boolean,
    val ignoringBatteryOptimizations: Boolean,
)

class BackgroundRestrictionReader(private val context: Context) {
    fun read(): BackgroundRestrictionSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return BackgroundRestrictionSnapshot(
            backgroundRestricted = activityManager.isBackgroundRestricted,
            ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName),
        )
    }
}
