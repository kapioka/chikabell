# ちかベル ロードマップ / ToDo

確認日: 2026-10-14（Asia/Tokyo）。次回更新に向けて選定済みの作業を管理する。公開済みはv0.1.3、端末で検証中の候補は0.2.0-rc1/code6。候補の一般公開完了は未判定。

詳細設計・Acceptance・Issueの状態はGitHubの個別Issueを正本とし、この文書は選定項目への入口とする。検索契約は[地点Import Contract](ai-import-contract.md)、通知の実地証拠は[近接通知検証](nearby_notification_validation.md)を参照する。

## 次回更新の選定済み作業

- [ ] [#16 Android共有でChatGPTの地点検索結果を取り込む](https://github.com/kapioka/chikabell/issues/16) — 実装・自動検証済み。公開AI契約はPR #21でmainへ統合済み。端末でのChatGPT往復・取り込み確認が未完了。
  - アプリでエリア・探したいもの・条件・件数を入力し、「ChatGPTで探す」から依頼する。
  - 公開AI入口 `ai/AGENTS.md` のURL、ユーザー条件、仕様取得失敗時は推測生成しない指示を含む短い依頼文を自動生成する。
  - ChatGPT側が必要な最新Import Contract / Schema / Workflowを参照して地点を検索する。アプリ側の仕様全文取得・ETag・キャッシュは基本経路に含めない。
  - JSONの貼り付け・Android共有・ファイルを既存Parser / Validation / 重複判定 / Import Previewへ合流し、ユーザー確認後だけ保存する。
  - 既存JSON形式を再利用できるか確認し、別Bundle Schemaを無条件に新設しない。OpenAI APIキー・独自サーバー・Google Drive接続を必須にしない。
  - ChatGPTへの事前入力は実装時点で動作を確認し、利用できない場合は依頼文コピー＋ChatGPT起動を使う。

Recipe Cooking Navigatorの[#7](https://github.com/kapioka/recipe-cooking-navigator/issues/7)と共通の操作方式で進める。共通化するのは「条件入力 → AI入口URL付き依頼 → ChatGPTで検索／生成 → 戻して検証・確認・保存」という体験であり、両アプリのデータ形式を一つに統合する決定ではない。

- [ ] [#17 ChatGPT接続でアプリ内地点検索](https://github.com/kapioka/chikabell/issues/17) — 独立PoCの本人認証・モデル取得・完了本文受信をPixel 10aで確認し、本体へ統合。暗号化保存、現在地の明示送信、モデル選択、独立した住所候補確認、選択プレビューを実装。本体の本人接続はユーザーが確認、実検索・保存と追加の接続QAは未完了。
- [ ] [#18 背景Geofence受信のANR対策](https://github.com/kapioka/chikabell/issues/18) — 修正版v0.1.3公開済み。隔離packageの受信worker・繰り返し終了2 testsを再確認。自然な背景Geofence、再起動、画面OFF等の実地証拠は継続中。
- [ ] 依存更新PR 8件 — 一括の互換構成をローカルで検証済み（Gradle9.6.1 / AGP9.2.1 / KSP2.3.10 / compileSdk37等）。公開CIと統合後に旧PRを置換として整理する。

## 改善候補の管理

全体の棚卸しは[#15 次期改善候補を棚卸しして優先順位を決める](https://github.com/kapioka/chikabell/issues/15)で管理する。Review candidatesは未選定の候補であり、すべてを実装すると決めたものではない。

## 再開・完了判定

- 次期作業の確認時は、このリストに加えてGitHubのopen Issueと対象Issueの最新本文を読む。PR一覧だけでは未着手の作業を把握できない。
- 個別IssueのAcceptanceを満たすまで、このリストを完了にしない。実装・自動検証・端末での往復確認・公開を分けて記録する。
- コード・自動検証、本人操作、実地QA、GitHub公開の状態を分ける。209 JVM tests、13 SIWC tests、5隔離device testsの成功と、正式署名候補の証明書一致・データ保持更新を確認。実地未測定や一般公開を成功として扱わない。
