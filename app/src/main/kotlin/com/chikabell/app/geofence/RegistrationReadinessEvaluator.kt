package com.chikabell.app.geofence

import com.chikabell.app.permission.BackgroundLocationStatus
import com.chikabell.app.permission.ForegroundLocationStatus
import com.chikabell.app.permission.GooglePlayServicesStatus
import com.chikabell.app.permission.LocationServicesStatus
import com.chikabell.app.permission.PermissionSnapshot

object RegistrationReadinessEvaluator {
    fun evaluate(permissionSnapshot: PermissionSnapshot, enabledLocationCount: Int): RegistrationReadiness {
        return when {
            permissionSnapshot.googlePlayServices != GooglePlayServicesStatus.Available ->
                RegistrationReadiness.Blocked("Google Play services が利用できません")
            permissionSnapshot.locationServices != LocationServicesStatus.Enabled ->
                RegistrationReadiness.Blocked("端末の位置情報がOFFです")
            permissionSnapshot.foregroundLocation != ForegroundLocationStatus.Precise ->
                RegistrationReadiness.Blocked("正確な位置情報の許可が必要です")
            permissionSnapshot.backgroundLocation != BackgroundLocationStatus.Granted &&
                permissionSnapshot.backgroundLocation != BackgroundLocationStatus.NotRequired ->
                RegistrationReadiness.Blocked("バックグラウンド位置情報の許可が必要です")
            enabledLocationCount == 0 ->
                RegistrationReadiness.Blocked("有効な地点がありません")
            enabledLocationCount > MAX_GEOFENCES ->
                RegistrationReadiness.Blocked("有効地点は${MAX_GEOFENCES}件までです")
            else -> RegistrationReadiness.Ready
        }
    }

    private const val MAX_GEOFENCES = 100
}

sealed interface RegistrationReadiness {
    data object Ready : RegistrationReadiness
    data class Blocked(val reason: String) : RegistrationReadiness
}
