package com.chikabell.app.ui.locations

import com.chikabell.app.permission.ActivityRecognitionStatus
import com.chikabell.app.permission.BackgroundLocationStatus
import com.chikabell.app.permission.BackgroundRestrictionSnapshot
import com.chikabell.app.permission.ForegroundLocationStatus
import com.chikabell.app.permission.GooglePlayServicesStatus
import com.chikabell.app.permission.LocationServicesStatus
import com.chikabell.app.permission.NotificationPermissionStatus
import com.chikabell.app.permission.PermissionSnapshot

internal enum class PermissionSettingHealth {
    READY,
    ACTION_REQUIRED,
    OPTIONAL,
    CHECKING,
}

enum class PermissionSettingsAction {
    REQUEST_FOREGROUND_LOCATION,
    REQUEST_NOTIFICATION,
    REQUEST_ACTIVITY_RECOGNITION,
    OPEN_APP_SETTINGS,
    OPEN_LOCATION_SETTINGS,
    OPEN_GOOGLE_PLAY_SERVICES_SETTINGS,
}

internal data class PermissionSettingItem(
    val title: String,
    val currentStatus: String,
    val recommendedStatus: String,
    val purpose: String,
    val health: PermissionSettingHealth,
)

internal data class PermissionSettingsPresentation(
    val summaryTitle: String,
    val summaryMessage: String,
    val items: List<PermissionSettingItem>,
    val primaryAction: PermissionSettingsAction,
    val primaryActionLabel: String,
    val requiredActionCount: Int,
)

internal fun permissionSettingsPresentation(
    snapshot: PermissionSnapshot,
    backgroundRestriction: BackgroundRestrictionSnapshot?,
): PermissionSettingsPresentation {
    val notification = PermissionSettingItem(
        title = "通知",
        currentStatus = when (snapshot.notificationPermission) {
            NotificationPermissionStatus.Granted -> "許可済み"
            NotificationPermissionStatus.Denied -> "未許可"
            NotificationPermissionStatus.NotRequired -> "設定不要"
        },
        recommendedStatus = "許可",
        purpose = "近くに来たことを通知するために必要です。",
        health = when (snapshot.notificationPermission) {
            NotificationPermissionStatus.Denied -> PermissionSettingHealth.ACTION_REQUIRED
            else -> PermissionSettingHealth.READY
        },
    )
    val foregroundLocation = PermissionSettingItem(
        title = "位置情報",
        currentStatus = when (snapshot.foregroundLocation) {
            ForegroundLocationStatus.Precise -> "正確な位置を許可済み"
            ForegroundLocationStatus.ApproximateOnly -> "おおよその位置のみ"
            ForegroundLocationStatus.Denied -> "未許可"
        },
        recommendedStatus = "正確な位置を許可",
        purpose = "地点との距離を正しく判定するために必要です。",
        health = if (snapshot.foregroundLocation == ForegroundLocationStatus.Precise) {
            PermissionSettingHealth.READY
        } else {
            PermissionSettingHealth.ACTION_REQUIRED
        },
    )
    val backgroundLocation = PermissionSettingItem(
        title = "バックグラウンド位置情報",
        currentStatus = when (snapshot.backgroundLocation) {
            BackgroundLocationStatus.Granted -> "許可済み"
            BackgroundLocationStatus.Denied -> "未許可"
            BackgroundLocationStatus.NotRequired -> "設定不要"
        },
        recommendedStatus = "常に許可",
        purpose = "アプリを閉じている間も近づきを検知するために必要です。",
        health = when (snapshot.backgroundLocation) {
            BackgroundLocationStatus.Denied -> PermissionSettingHealth.ACTION_REQUIRED
            else -> PermissionSettingHealth.READY
        },
    )
    val locationServices = PermissionSettingItem(
        title = "端末の位置情報",
        currentStatus = if (snapshot.locationServices == LocationServicesStatus.Enabled) "ON" else "OFF",
        recommendedStatus = "ON",
        purpose = "端末が位置情報を取得できる状態にします。",
        health = if (snapshot.locationServices == LocationServicesStatus.Enabled) {
            PermissionSettingHealth.READY
        } else {
            PermissionSettingHealth.ACTION_REQUIRED
        },
    )
    val googlePlayServices = PermissionSettingItem(
        title = "Google Play services",
        currentStatus = when (snapshot.googlePlayServices) {
            GooglePlayServicesStatus.Available -> "利用可能"
            GooglePlayServicesStatus.UserResolvableError -> "更新または対応が必要"
            GooglePlayServicesStatus.Unavailable -> "利用できません"
        },
        recommendedStatus = "利用可能",
        purpose = "登録した地点の監視に必要です。",
        health = if (snapshot.googlePlayServices == GooglePlayServicesStatus.Available) {
            PermissionSettingHealth.READY
        } else {
            PermissionSettingHealth.ACTION_REQUIRED
        },
    )
    val activityRecognition = PermissionSettingItem(
        title = "活動認識",
        currentStatus = when (snapshot.activityRecognition) {
            ActivityRecognitionStatus.Granted -> "許可済み"
            ActivityRecognitionStatus.Denied -> "未許可（速度で代替）"
            ActivityRecognitionStatus.NotRequired -> "設定不要"
        },
        recommendedStatus = "許可（任意）",
        purpose = "移動中の判定を補助します。未許可でも基本機能は使えます。",
        health = when (snapshot.activityRecognition) {
            ActivityRecognitionStatus.Denied -> PermissionSettingHealth.OPTIONAL
            else -> PermissionSettingHealth.READY
        },
    )
    val battery = PermissionSettingItem(
        title = "バッテリーのバックグラウンド制限",
        currentStatus = when {
            backgroundRestriction == null -> "確認中"
            backgroundRestriction.backgroundRestricted -> "制限あり"
            backgroundRestriction.ignoringBatteryOptimizations -> "最適化対象外"
            else -> "制限なし"
        },
        recommendedStatus = "制限なし",
        purpose = "バックグラウンド動作が端末に止められていないかを確認します。",
        health = when {
            backgroundRestriction == null -> PermissionSettingHealth.CHECKING
            backgroundRestriction.backgroundRestricted -> PermissionSettingHealth.ACTION_REQUIRED
            else -> PermissionSettingHealth.READY
        },
    )

    val items = listOf(
        notification,
        foregroundLocation,
        backgroundLocation,
        locationServices,
        googlePlayServices,
        activityRecognition,
        battery,
    )
    val requiredActionCount = items.count { it.health == PermissionSettingHealth.ACTION_REQUIRED }
    val isChecking = items.any { it.health == PermissionSettingHealth.CHECKING }
    val optionalActionAvailable = snapshot.activityRecognition == ActivityRecognitionStatus.Denied

    val primaryAction = when {
        snapshot.googlePlayServices != GooglePlayServicesStatus.Available ->
            PermissionSettingsAction.OPEN_GOOGLE_PLAY_SERVICES_SETTINGS
        snapshot.locationServices == LocationServicesStatus.Disabled ->
            PermissionSettingsAction.OPEN_LOCATION_SETTINGS
        snapshot.foregroundLocation == ForegroundLocationStatus.Denied ->
            PermissionSettingsAction.REQUEST_FOREGROUND_LOCATION
        snapshot.foregroundLocation == ForegroundLocationStatus.ApproximateOnly ->
            PermissionSettingsAction.OPEN_APP_SETTINGS
        snapshot.backgroundLocation == BackgroundLocationStatus.Denied ->
            PermissionSettingsAction.OPEN_APP_SETTINGS
        snapshot.notificationPermission == NotificationPermissionStatus.Denied ->
            PermissionSettingsAction.REQUEST_NOTIFICATION
        backgroundRestriction?.backgroundRestricted == true ->
            PermissionSettingsAction.OPEN_APP_SETTINGS
        optionalActionAvailable ->
            PermissionSettingsAction.REQUEST_ACTIVITY_RECOGNITION
        else -> PermissionSettingsAction.OPEN_APP_SETTINGS
    }
    val primaryActionLabel = when (primaryAction) {
        PermissionSettingsAction.REQUEST_FOREGROUND_LOCATION -> "位置情報を許可する"
        PermissionSettingsAction.REQUEST_NOTIFICATION -> "通知を許可する"
        PermissionSettingsAction.REQUEST_ACTIVITY_RECOGNITION -> "活動認識を許可する（任意）"
        PermissionSettingsAction.OPEN_LOCATION_SETTINGS -> "端末の位置情報設定を開く"
        PermissionSettingsAction.OPEN_GOOGLE_PLAY_SERVICES_SETTINGS -> "Google Play services を確認する"
        PermissionSettingsAction.OPEN_APP_SETTINGS -> if (requiredActionCount > 0) {
            "必要なAndroid設定を開く"
        } else {
            "Androidのアプリ設定を開く"
        }
    }

    return PermissionSettingsPresentation(
        summaryTitle = when {
            requiredActionCount > 0 -> "設定が必要な項目があります"
            isChecking -> "一部の状態を確認中です"
            else -> "必要な設定は完了しています"
        },
        summaryMessage = when {
            requiredActionCount > 0 -> "${requiredActionCount}件を見直すと、近くでの通知が正しく動作しやすくなります。"
            isChecking -> "確認が終わるまで少しお待ちください。"
            optionalActionAvailable -> "必須項目は完了しています。活動認識は任意で追加できます。"
            else -> "現在、操作が必要な項目はありません。"
        },
        items = items,
        primaryAction = primaryAction,
        primaryActionLabel = primaryActionLabel,
        requiredActionCount = requiredActionCount,
    )
}
