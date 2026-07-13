# 公開準備状況

確認日: 2026-07-14

## ソース公開

- 公開専用の新しいGit履歴を使用し、個人用開発履歴を引き継がない。
- 実機snapshot、地点export、端末ログ、生成APK、署名情報を公開対象から除外した。
- 実端末シリアル、個人用地点座標、ローカル絶対パスの既知マーカーはステージ済みファイルで0件。
- JVMテスト44件、debug lint/build、release lint/buildは成功。
- `check_secrets.ps1`と`detect-secrets`の検出結果を確認済み。環境変数参照、Kotlinの`key`識別子、Room schemaの`identityHash`だけで、実秘密は検出されなかった。

## GitHubで一般公開する直前のゲート

- リポジトリのPrivate vulnerability reportingを有効にする。
- ライセンスを付ける場合は、所有者が用途に合うライセンスを選択する。
- 最終ステージ差分へ秘密情報スキャンを再実行する。
- GitHub Actionsの初回実行でwrapper validation、test、lint、debug buildが成功することを確認する。

## APK配布の追加ゲート

- `CHIKABELL_*`環境変数で継続利用する署名鍵を設定する。
- `tools/build_release.ps1`を実行し、署名検証とSHA-256生成を成功させる。
- Pixel 10aへ`adb install -r`で更新し、データ保持、起動、共有受信、位置情報権限、通知を確認する。
- GitHub Releaseへは署名済みAPKと`SHA256SUMS.txt`だけを添付する。

署名がない場合、`tools/build_release.ps1`は失敗終了し、`dist/`へAPKをコピーしない。
