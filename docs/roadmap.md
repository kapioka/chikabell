# ちかベル ロードマップ / ToDo

## v0.2.0-rc2の画面改善

地点追加の入口統合、AI検索・候補確認の2画面化、必要時に開く設定シート、IME表示中の主要操作、JSON本文の折りたたみを反映した候補版。[変更内容と検証範囲](releases/v0.2.0-rc2.md)を参照。以下の実地・接続の未確認項目は継続し、画面改善の公開だけで完了扱いにしない。

確認日: 2026-10-14（Asia/Tokyo）。次回更新に向けて選定済みの作業を管理する。安定版はv0.1.3。検証用Prerelease v0.2.0-rc1/code6を公開し、端末版と公開APKの照合済み。実地ゲート未完了のため安定版昇格は未判定。

詳細設計・Acceptance・Issueの状態はGitHubの個別Issueを正本とし、この文書は選定項目への入口とする。検索契約は[地点Import Contract](ai-import-contract.md)、通知の実地証拠は[近接通知検証](nearby_notification_validation.md)を参照する。

## 次回更新の選定済み作業

- [ ] [#16 Android共有でChatGPTの地点検索結果を取り込む](https://github.com/kapioka/chikabell/issues/16) — 実装・自動検証済み。公開AI契約はPR #21でmainへ統合済み。コピー→ChatGPT→JSON貼り付け→Previewキャンセルの往復をユーザーが確認。共有variant・権限拒否・背景通知は別ゲート。
  - アプリでエリア・探したいもの・条件・件数を入力し、「ChatGPTで探す」から依頼する。
  - 公開AI入口 `ai/AGENTS.md` のURL、ユーザー条件、仕様取得失敗時は推測生成しない指示を含む短い依頼文を自動生成する。
  - ChatGPT側が必要な最新Import Contract / Schema / Workflowを参照して地点を検索する。アプリ側の仕様全文取得・ETag・キャッシュは基本経路に含めない。
  - JSONの貼り付け・Android共有・ファイルを既存Parser / Validation / 重複判定 / Import Previewへ合流し、ユーザー確認後だけ保存する。
  - 既存JSON形式を再利用できるか確認し、別Bundle Schemaを無条件に新設しない。OpenAI APIキー・独自サーバー・Google Drive接続を必須にしない。
  - ChatGPTへの事前入力は実装時点で動作を確認し、利用できない場合は依頼文コピー＋ChatGPT起動を使う。

Recipe Cooking Navigatorの[#7](https://github.com/kapioka/recipe-cooking-navigator/issues/7)と共通の操作方式で進める。共通化するのは「条件入力 → AI入口URL付き依頼 → ChatGPTで検索／生成 → 戻して検証・確認・保存」という体験であり、両アプリのデータ形式を一つに統合する決定ではない。

- [ ] [#17 ChatGPT接続でアプリ内地点検索](https://github.com/kapioka/chikabell/issues/17) — 独立PoCの本人認証・モデル取得・完了本文受信をPixel 10aで確認し、本体へ統合。本体の本人接続、実検索・候補選択・承認保存をユーザーが確認。登録前の通知半径・メモ・タグ編集も自動検証済み。GPS検索・位置確認・半径/メモ編集・Previewキャンセルをユーザーが確認。Web検索の個別完了状態、追加の接続QAと背景通知は別ゲート。
- [ ] [#18 背景Geofence受信のANR対策](https://github.com/kapioka/chikabell/issues/18) — 修正版v0.1.3公開済み。隔離packageの受信worker・繰り返し終了2 testsを再確認。自然な背景Geofence、再起動、画面OFF等の実地証拠は継続中。
- [x] 依存更新PR 8件 — 互換構成のテスト・公開CIが成功しPR #22をmainへ統合。旧PR #2/#3/#4/#5/#6/#7/#8/#14は置換理由を記してclosed。

- 更新前の通知・画面OFF・12時間休止・再起動はすべて動作していたとユーザーが回答。v0.1.3と候補版のGeofence/通知/復元/DB schemaは同一、Room/WorkManager/Play services locationの版も同一で、既存機能の証拠として再利用する。取り込みtransactionとCore/Lifecycle等は変わったため、候補版の新規登録地点の背景通知は重点確認対象。候補版で全実地ゲートを再測定済みとは扱わない。

## 改善候補の管理

全体の棚卸しは[#15 次期改善候補を棚卸しして優先順位を決める](https://github.com/kapioka/chikabell/issues/15)で管理する。Review candidatesは未選定の候補であり、すべてを実装すると決めたものではない。

## 再開・完了判定

- 次期作業の確認時は、このリストに加えてGitHubのopen Issueと対象Issueの最新本文を読む。PR一覧だけでは未着手の作業を把握できない。
- 個別IssueのAcceptanceを満たすまで、このリストを完了にしない。実装・自動検証・端末での往復確認・公開を分けて記録する。
- コード・自動検証、本人操作、実地QA、GitHub公開の状態を分ける。211 JVM tests、14 SIWC tests、5隔離device testsの成功と、正式署名候補の証明書一致・データ保持更新を確認。実地未測定や一般公開を成功として扱わない。
