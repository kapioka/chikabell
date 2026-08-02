# 公開準備状況

確認日: 2026-08-01

## 公開方針

- GitHubアカウントの既存公開物と同じ名義を使用する。
- ソースコードと署名済みAPKを公開対象とする。
- Play Storeでは公開しない。
- 動作、通知の到達、継続的な更新、不具合修正を保証しない。
- GitHub IssuesおよびPull Requestは受け付けない。
- ライセンスは現時点で指定しない。

## ソース公開ゲート

- 個人用開発リポジトリの履歴を引き継がず、公開専用の新しいGit履歴を使用する。
- 実機snapshot、地点export、端末ログ、ローカルパス、生成APK、署名情報を公開対象から除外する。
- 公開対象全体の秘密情報・個人情報スキャンを再実行する。
- JVMテスト、lint、debug build、release buildを実行する。
- GitHub Actionsの初回実行結果は、公開後の別ゲートとして確認する。

## GitHub設定ゲート

- Issues、Discussions、Wikiを無効にする。
- 外部からのPull Requestを受け付けない方針をREADMEへ表示する。
- リポジトリ説明とTopicsを設定する。
- ライセンス欄は未指定のままにする。

## APK配布ゲート

- 所有者が保管場所を決めた後、初回署名鍵を対話的に作成する。
- 署名鍵とパスワードをリポジトリ、Issue、Release、Markdownへ保存しない。
- `tools/build_release.ps1`で署名、テスト、lint、release build、SHA-256生成を検証する。
- Pixel 10aへ`adb install -r`で更新し、データ保持、起動、共有受信、位置情報権限、通知を確認する。
- GitHub Releaseには署名済みAPKと`SHA256SUMS.txt`だけを添付する。

## 外部操作

GitHubリポジトリ作成、push、tag、Release作成は未実施。所有者の明示的な公開許可が出るまで行わない。
