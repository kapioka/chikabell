# ちかベル (ChikaBell)

Googleマップ等から共有した地点や手入力した地点へ近づいたとき、AndroidのGeofencing APIで通知し、端末内に履歴を残す個人利用向けアプリです。

## 現在の状態

- バージョン: `0.1.0`
- 対応OS: Android 10（API 29）以上
- 実機検証: Pixel 10a（検証時点のAndroid環境）
- 配布方針: GitHub Releasesを予定。Play Store公開予定なし

## 主な機能

- 地点の追加・編集・削除
- Googleマップ等の共有テキスト・座標受信（Googleマップ短縮URLはGoogleのHTTPSサーバーで展開）
- 半径、滞在秒、再通知間隔の地点別設定とテンプレ
- 通知時の端末現在地・精度・登録地点からの距離を履歴へ記録
- 画面OFF・バックグラウンドでのGeofence監視
- 通知・検証履歴
- 再起動・アプリ更新後の監視復元と診断
- JSONバックアップ・復元
- UTF-8 BOM付きCSV入出力と安全なimport preview

## 権限

- 正確な位置情報: 地点との距離を判定するため
- バックグラウンド位置情報: 画面OFF・アプリ非表示時もGeofence通知を受けるため
- 通知: 接近通知を表示するため
- 起動完了受信: 端末再起動後にGeofence監視を復元するため

本アプリはGoogleマップ短縮URLを展開するために`INTERNET`権限を使用します。通信先はGoogle Maps関連のHTTPSホストに限定し、独自サーバー・広告・解析SDKは使用しません。保存済み地点や通知履歴をアプリ独自に外部送信する機能はありません。接近検知には端末のGoogle Play servicesを使用します。

## プライバシーとバックアップ

地点、座標、メモ、通知履歴は端末内のRoomデータベースへ保存します。Android自動バックアップは無効です。機種移行や保全には、アプリ内のJSONバックアップを利用してください。

JSON/CSVには座標やメモが含まれます。公開リポジトリ、Issue、チャット、共有ストレージへ誤って添付しないでください。詳細は[PRIVACY.md](PRIVACY.md)を参照してください。

## 既知の制限

- Geofence通知はAndroid、Google Play services、位置精度、バッテリー設定に依存し、即時性や100%の受信を保証しません。
- 短縮Maps URLはGoogle Maps関連ホストへ接続して展開しますが、応答形式や通信状態によって座標を取得できない場合は手入力が必要です。
- アプリを強制停止すると、ユーザーが再度起動するまでAndroidが背景処理を止めます。
- Android 12以降では、`allowBackup=false`でも端末メーカーによって端末間転送が許可される場合があります。

## 開発・検証

前提:

- JDK 17
- Android SDK 36
- Windows PowerShell（このリポジトリの既存検証環境）

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

release候補の検証:

```powershell
.\tools\build_release.ps1
```

署名環境変数が未設定の場合、`assembleRelease`はunsigned APKを生成します。GitHubへ配布するAPKは必ず同一の秘密鍵で署名してください。署名方法は[docs/github_release.md](docs/github_release.md)を参照してください。

## インストール

GitHub Releasesから署名済みAPKを取得し、Androidの「不明なアプリのインストール」を明示的に許可して導入します。更新版は前版と同じ署名鍵で署名されている必要があります。

## ライセンス

現時点ではライセンス未指定です。公開リポジトリを閲覧できても、再配布・改変許諾が自動的に付与されるわけではありません。

## セキュリティ

脆弱性やプライバシー上の問題を見つけた場合は、座標・住所・共有URL・バックアップ・通知履歴を公開Issueへ添付しないでください。報告時の注意事項は[SECURITY.md](SECURITY.md)を参照してください。
