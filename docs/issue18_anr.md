# Geofence受信処理のANR対策（#18）

## 原因と変更

受信処理が約60秒の期限を超えたBroadcast ANRを6件確認した。
サービスを起動できなかった場合やEXIT受信時に、最大180秒の位置確認や
共有Mutexの待機を受信側で直接待つ経路があった。
各発生時の具体的な待機箇所やサービス起動を拒否した理由は、既存トレースだけでは断定できない。

- ENTER/DWELLで確認サービスを起動できる通常経路を維持。
- サービス起動拒否とEXITをWorkManagerへ引き継ぎ、受信側は永続化の完了だけを待つ。
- 引き継ぎを8秒に制限し、成功・失敗・キャンセル時に受信の終了処理を行う。
- Workerは既存の位置確認・通知判定・EXITキャンセルを再利用する。
- Workerの180秒の処理予算を超えた場合は最大2回のバックオフ再試行を行う。
- 診断ログには例外クラスを記録し、座標や例外本文は記録しない。
- 地点DBスキーマと権限は変更しない。

## 検証

- JVM unit tests 195件合格。
- サービス成功・拒否、EXIT、重複入力、永続化の停止・失敗を検証。
- 位置確認を保留した実UseCaseでも受信の引き継ぎが返り、EXITがキャンセルできる回帰テストを追加。
- release lint error 0、正式署名APK生成成功。
- Pixel 10a / Android 17で隔離QAのAndroidテスト2件合格。
- 隔離QAで実WorkManagerの実行と、実Receiverへの20回のEXIT受信終了を確認。
- 通常版を同じ署名でデータ保持更新し、起動・権限・回転設定の保持、新しいANR・クラッシュがないことを確認。

## 隔離QAの再現

通常版の地点や権限を読み書きしない、別packageで実行する。
位置権限の追加や架空GeofenceのPlay servicesへの登録は行わない。
`GeofenceEventHandoffDeviceTest` は隔離package以外ではスキップする。

```powershell
.\gradlew.bat --init-script .\tools\issue18qa.init.gradle assembleDebug assembleDebugAndroidTest
adb -s '<target serial>' install -r .\app\build\issue18qa\outputs\apk\debug\app-debug.apk
adb -s '<target serial>' install -r .\app\build\issue18qa\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s '<target serial>' shell am instrument -w -e class com.chikabell.app.GeofenceEventHandoffDeviceTest com.chikabell.app.issue18qa.test/androidx.test.runner.AndroidJUnitRunner
```

検証前後に回転設定を読み取り、変更しない。通常版への更新は `adb install -r` を使用する。
アンインストール・データ消去・`adb shell monkey`は行わない。

## 残る確認と制限

WorkManagerへの引き継ぎ後、実行開始はAndroidのスケジューリングにより遅れる場合がある。
永続化の失敗や8秒超過時は診断を残して受信を終了し、そのイベントの保存成功を保証しない。

実際の移動を伴う接近・退出通知と、他アプリ利用中の背景監視は継続確認する。
隔離QAと起動確認は、実Geofence通知の検証を代替しない。
スクリーンショットの遅延と今回のANRの因果関係は未確定。

## 公式参照

- [Broadcast ANRの診断](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs)
- [Broadcastのライフサイクル](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
- [WorkManager Operation](https://developer.android.com/reference/androidx/work/Operation)
