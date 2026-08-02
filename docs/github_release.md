# GitHub Release手順

## 原則

- GitHubへ置くAPKは署名済みだけとする。
- 初回配布から同じ署名鍵を継続使用する。鍵を失うと既存インストールへ更新できない。
- keystore、alias、パスワードはリポジトリ、Issue、release asset、Markdownへ保存しない。
- `*.jks`、`*.keystore`、`keystore.properties`、APK/AAB、`dist/`は`.gitignore`対象とする。
- GitHubへのpush、tag、Release作成は、公開直前の監査結果を確認して所有者が明示的に許可した後だけ行う。

## 初回署名鍵を作る前に

- 鍵の保存先を、リポジトリ外のバックアップ可能な場所に決める。
- パスワードはパスワードマネージャー等で管理し、Markdown、Issue、シェル履歴へ残さない。
- 初回配布に使った鍵は、以後のAPK更新でも継続して使用する。鍵を失うと既存インストールへ更新できない。
- 鍵の生成コマンドは公開作業時に所有者と確認して実行する。準備段階では鍵を自動生成しない。

## ローカル署名設定

次の環境変数を現在のPowerShellセッションだけへ設定する。

```powershell
Set-Item -LiteralPath Env:CHIKABELL_KEYSTORE_PATH -Value 'C:\secure\chikabell-release.jks'
Set-Item -LiteralPath Env:CHIKABELL_KEYSTORE_PASSWORD -Value (Read-Host 'Keystore password' -MaskInput)
Set-Item -LiteralPath Env:CHIKABELL_KEY_ALIAS -Value (Read-Host 'Key alias')
Set-Item -LiteralPath Env:CHIKABELL_KEY_PASSWORD -Value (Read-Host 'Key password' -MaskInput)
```

値は例示せず、実値をドキュメント・シェル履歴・スクリーンショットへ残さない。

## ビルド

```powershell
.\tools\build_release.ps1
```

スクリプトは署名設定を先に確認し、単体テスト、lint、release build、`apksigner`による署名検証、SHA-256生成を行う。署名環境変数、keystore、`apksigner`のいずれかが不足する場合や署名検証に失敗した場合はエラー終了し、`dist/`へ新しい配布物を作成しない。

## 公開前チェック

1. `git status --short`が空である。
2. `check_secrets.ps1`の警告を確認する。
3. APKが署名済みである。
4. APKのSHA-256をrelease noteへ記載する。
5. Pixel 10aへ更新インストールし、起動・地点保持・共有受信・JSON出力を確認する。
6. release assetへJSON/CSV、端末snapshot、keystoreを含めない。
7. GitHub Release本文へ既知制限と対応Androidバージョンを記載する。
8. GitHub SettingsでIssues、Discussions、Wikiを無効にし、外部からのPull Requestを受け付けない方針をREADMEへ明記する。
9. ライセンス未指定であることと、利用・改変・再配布許諾をまだ与えていないことをREADMEへ明記する。

## release asset候補

- `chikabell-v0.1.0.apk`
- `SHA256SUMS.txt`

Play Store用AAB、ストア掲載文、Data Safety申告は対象外。
