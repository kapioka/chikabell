# GitHub Release手順

## 原則

- GitHubへ置くAPKは署名済みだけとする。
- 初回配布から同じ署名鍵を継続使用する。鍵を失うと既存インストールへ更新できない。
- keystore、alias、パスワードはリポジトリ、Issue、release asset、Markdownへ保存しない。
- `*.jks`、`*.keystore`、`keystore.properties`、APK/AAB、`dist/`は`.gitignore`対象とする。

## ローカル署名設定

次の環境変数を現在のPowerShellセッションだけへ設定する。

```powershell
$env:CHIKABELL_KEYSTORE_PATH = 'C:\secure\chikabell-release.jks'
$env:CHIKABELL_KEYSTORE_PASSWORD = '<secret>'
$env:CHIKABELL_KEY_ALIAS = '<alias>'
$env:CHIKABELL_KEY_PASSWORD = '<secret>'
```

値は例示せず、実値をドキュメント・シェル履歴・スクリーンショットへ残さない。

## ビルド

```powershell
.\tools\build_release.ps1
```

スクリプトは単体テスト、lint、release build、APK署名状態、SHA-256を検証し、結果を`dist/`へ出力する。署名環境変数が未設定の場合はunsigned APKとして生成し、配布不可であることを明示する。

## 公開前チェック

1. `git status --short`が空である。
2. ステージ済みファイルへ秘密情報スキャンを実行する。このCodex環境では`& "$HOME\.codex\scripts\check_secrets.ps1" -RepoRoot . -StagedOnly`を使用し、利用できない環境ではGitHub secret scanningや同等のscannerで代替する。
3. APKが署名済みである。
4. APKのSHA-256をrelease noteへ記載する。
5. Pixel 10aへ更新インストールし、起動・地点保持・共有受信・JSON出力を確認する。
6. release assetへJSON/CSV、端末snapshot、keystoreを含めない。
7. GitHub Release本文へ既知制限と対応Androidバージョンを記載する。
8. 公開元が個人用開発リポジトリの場合は、実機snapshotや座標を含む履歴を引き継がず、公開専用のクリーンなGit履歴を使用する。
9. GitHubリポジトリのPrivate vulnerability reportingを有効にしてから一般公開する。

## release asset候補

- `chikabell-v<versionName>.apk`
- `SHA256SUMS.txt`

Play Store用AAB、ストア掲載文、Data Safety申告は対象外。
