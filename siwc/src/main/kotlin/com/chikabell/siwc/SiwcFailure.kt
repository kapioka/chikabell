package com.chikabell.siwc

import kotlinx.serialization.json.*

/** Only fixed classifications are exposed; exception text and remote response bodies remain private. */
object SiwcFailure {
    private val serverCodes = setOf("subscription_sharing_user_not_eligible", "subscription_sharing_usage_limit_exceeded",
        "subscription_sharing_usage_unavailable", "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported",
        "subscription_sharing_invalid_user", "subscription_sharing_user_unavailable", "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context", "invalid_grant")
    fun knownServerCode(error: JsonElement?): String? = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull?.takeIf { it in serverCodes }
    fun explanation(error: Exception): String = when {
        error is java.net.UnknownHostException -> "名前解決に失敗しました"
        error is java.net.SocketTimeoutException -> "通信時間切れです"
        error is javax.net.ssl.SSLException -> "TLS接続を検証できません"
        error is java.net.SocketException -> "ネットワーク接続が中断されました"
        error.message?.matches(Regex("http_[0-9]{3}")) == true -> "HTTP " + error.message!!.removePrefix("http_")
        else -> when (error.message) {
            "plan_permission_missing" -> "ChatGPTプラン利用の許可がありません"
            "reauthorize" -> "ChatGPTに再接続してください"
            "authorization_denied" -> "認証が拒否されました"
            "authorization_timeout" -> "認証待ちの時間切れです"
            "account_mismatch" -> "アカウントが一致しません"
            "response_too_large", "stream_too_large" -> "応答が受信サイズ上限を超えました"
            "response_timeout" -> "応答の受信時間が上限を超えました"
            "response_json_invalid", "response_object_invalid" -> "応答のJSON形式を確認できません"
            "model_catalog_invalid" -> "モデル一覧のmodels配列を確認できません"
            "model_catalog_empty", "model_unavailable" -> "選択したモデルを利用できません"
            "inference_failed" -> "推論が失敗または未完了です"
            "stream_interrupted" -> "完了イベント前に通信が終了しました"
            "empty_response" -> "完了応答の本文が空です"
            "account_limit" -> "接続の保存上限に達しました"
            "location_stale" -> "現在地が古くなりました。取得し直してください"
            "subscription_sharing_usage_limit_exceeded" -> "この接続のChatGPT利用枠の上限に達しました。利用状況を確認してください"
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> "ChatGPTの利用可否を確認できません。接続を保持し、時間をおいて再試行してください"
            "subscription_sharing_user_not_eligible" -> "このアカウントまたはワークスペースではプラン共有を利用できません"
            "subscription_sharing_unsupported_capability" -> "選択したモデル・Web検索などの機能が非対応です。コピー・共有経路も利用できます"
            "subscription_sharing_route_not_supported" -> "この利用方法が許可されていません"
            "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "接続の本人情報または利用許可を検証できません"
            "invalid_grant" -> "接続期限の更新が拒否されました。再接続してください"
            else -> "応答または保護保存を検証できません"
        }
    }
}
