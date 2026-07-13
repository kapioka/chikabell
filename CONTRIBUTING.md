# コントリビューション

IssueやPull Requestへ、実際の住所、座標、共有URL、バックアップ、通知履歴、端末snapshot、端末シリアルを含めないでください。テストには架空の地点名と公開して差し支えないサンプル座標だけを使用してください。

変更前後に次を実行してください。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

署名鍵、keystore、ローカルSDKパス、生成APKはコミットしないでください。
