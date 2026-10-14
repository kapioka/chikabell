# ChikaBell アプリ更新・GitHub Release手順

この文書を、開発完了後からPixel 10aへの更新、GitHub Releaseの公開確認までの正本とする。
単なるコマンド一覧ではなく、途中で止まったときにどこから再開するかも示す。

## 原則

- GitHubへ置くAPKは署名済みだけとする。
- 初回配布から同じ署名鍵を継続使用する。鍵を失うと既存インストールへ更新できない。
- keystore、alias、パスワードはリポジトリ、Issue、release asset、Markdownへ保存しない。
- `*.jks`、`*.keystore`、`keystore.properties`、APK/AAB、`dist/`は`.gitignore`対象とする。
- GitHubへのpush、tag、Release作成は、公開直前の監査結果を確認して所有者が明示的に許可した後だけ行う。
- 開発ツリーに未コミットの別作業がある場合は、クリーンな公開用ツリーへ今回の変更だけを反映する。`git add -A`や作業ツリー全体のコピーは行わない。
- Pixel 10aは`adb install -r`で更新し、アンインストール、アプリデータ削除、`adb shell monkey`は行わない。
- 公開完了は、ソース、main、tag、GitHub ReleaseのAPK、端末版の5点を照合して判断する。ビルド成功だけでは完了としない。

## Codexへ依頼するときのテンプレート

最初に公開範囲と外部操作の許可を明示すると、途中の確認往復を減らせる。

```text
ChikaBellを versionName <次の版> / versionCode <次の番号> として更新してください。
今回の機能変更だけを公開用ツリーへ反映し、既存の未コミット変更は混ぜないでください。
単体テスト、lint、署名APK生成、既存版との署名一致、Pixel 10aへのadb install -r、
データ保持・回転・起動・クラッシュ確認まで行ってください。
検証合格後のcommit、ブランチpush、PR作成、CI確認、mainへの統合、tag、
GitHub ReleaseとAPK/SHA256SUMS.txtの公開、公開物の再ダウンロード照合まで許可します。
秘密情報はC:\AndroidSigningの既存暗号化設定のみから取得し、リポジトリ保存、表示、チャット送信しないでください。
```

版名や公開範囲が未確定なら、公開操作へ入る前に一度だけ確認する。

## 全体の一本道

1. 開発用・公開用・GitHub・端末の現在版を読み取る。
2. 今回の公開対象ファイルを確定する。
3. 公開用ブランチへ対象だけを反映し、版数と配布物名を更新する。
4. 単体テスト、lint、debug buildを直列に実行する。
5. 登録済み設定を使い、Build-Release.cmdで署名release APKを作る。
6. APKの版数、SHA-256、署名証明書を確認する。
7. Pixel 10aへ`adb install -r`で更新し、データ保持と起動状態を確認する。
8. 公開直前の秘密情報検査を行う。
9. commit、push、PR、CI、main統合、tag、GitHub Releaseを順番に行う。
10. GitHubから公開物を新規一時フォルダへ再取得し、ローカルAPKと照合する。

途中で失敗した場合は、成功済みの段階を繰り返さず、下記の再開表に従う。

## 1. 現状確認と公開単位の確定

開発用ツリーと公開用ツリーは別物として扱う。現在の標準構成は次のとおり。

- 開発用: `E:\作ってみた\場所通知サービス`
- 公開用: `E:\作ってみた\場所通知サービス-public`
- GitHub: `kapioka/chikabell`

```powershell
$publicRepo = 'E:\作ってみた\場所通知サービス-public'
$releaseVersion = (Read-Host 'Release version（vなし。例: 0.1.3）').Trim()
$releaseCode = [int](Read-Host 'versionCode（例: 4）')
$releaseBranchSlug = (Read-Host 'branch slug（英数字とハイフン）').Trim()

git status -sb
git diff --stat

git -C $publicRepo status -sb
git -C $publicRepo log -5 --oneline --decorate
git -C $publicRepo remote -v

gh auth status
gh release list --repo kapioka/chikabell --limit 5
```

確認事項:

- 公開用ツリーがGitHubの`main`と同期している。
- GitHub最新版、端末版、`app/build.gradle.kts`の版数関係が説明できる。
- 公開する機能とファイルが列挙され、別件の未コミット変更を含まない。
- 公開用ツリーが汚れている場合は、内容を確認するまで先へ進まない。

## 2. 公開用ブランチと版数更新

公開用ツリーの`main`から、1リリース1ブランチを作る。

```powershell
Set-Location -LiteralPath $publicRepo
git switch main
git pull --ff-only
git switch -c "codex/$releaseBranchSlug-v$releaseVersion"
```

今回の変更だけを、レビュー可能なパッチまたは明示したファイル単位で反映する。公開対象が混在するファイルは、ファイル全体を盲目的にコピーせず、該当hunkだけを反映する。

最低限更新する版数:

- `app/build.gradle.kts`: `versionCode`と`versionName`
- `README.md`: 現在のバージョン
- `tools/build_release.ps1`: 出力APK名

更新後に確認する。

```powershell
git diff --stat
git diff --check
git diff
```

想定外のファイルが1つでもあれば、そのファイルを外すまでステージしない。

## 3. Android SDKとGradle検証

公開用ツリーへ個人用`local.properties`をコピーしない。SDKが見つからない場合は、そのPowerShellプロセスだけへ設定する。

```powershell
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Gradleは同じツリーで複数同時実行しない。解決不能参照が大量に出た場合はコードを直し始める前に、重複または残存Gradleプロセスが終わるのを待ち、1本のコマンドを直列で再実行する。

合格条件:

- 全JVMテストが成功する。
- lintが成功する。
- debug APKが生成される。
- 失敗を警告扱いに変更して通していない。

## 初回署名鍵を作る前に

- 鍵の保存先を、リポジトリ外のバックアップ可能な場所に決める。
- パスワードはパスワードマネージャー等で管理し、Markdown、Issue、シェル履歴へ残さない。
- 初回配布に使った鍵は、以後のAPK更新でも継続して使用する。鍵を失うと既存インストールへ更新できない。
- 鍵の生成コマンドは公開作業時に所有者と確認して実行する。準備段階では鍵を自動生成しない。

## ローカル署名設定（初回のみ）

プロジェクト直下の `Setup-Signing.cmd` をダブルクリックし、外部PowerShellで現在の正式版に使っている既存 `.p12` を選ぶ。aliasを確認し、パスワードを伏せ字入力で登録する。キーのパスワードが同じなら次の入力はEnterでよい。更新用の鍵は新しく作らない。

鍵は `C:\AndroidSigning\chikabell-release.p12` へコピーする。設定は同じフォルダの `chikabell.signing.clixml` に保存し、パスワードはWindows DPAPIで暗号化する。同じPC・Windowsユーザーでのみ復号できる。保存先のアクセス権は現在のユーザー・SYSTEM・Administratorsに限定する。平文 `.env`、永続環境変数、リポジトリ内の秘密情報は不要。

鍵と復旧パスワードは別途安全にバックアップする。別PCでは再登録が必要。公開用ツリーへの反映対象はスクリプト・cmd・Git除外・手順書のみで、`C:\AndroidSigning` の内容はコピーしない。

## ビルド（毎回の入口）

`Build-Release.cmd` をダブルクリックする。Codexも次の同じ入口を使う。

```powershell
pwsh -NoProfile -File .\tools\build_release.ps1
```

登録済み設定を自動取得し、単体テスト、lint、release build、署名検証と登録鍵の証明書照合、SHA-256生成を行う。Gradle daemonとconfiguration cacheは無効。署名情報はプロセス環境変数にだけ設定し、終了・失敗時に元の値へ戻す。未登録や鍵の変更は停止し、他の署名方法へ自動的に切り替えない。

成功時の出力は `dist/chikabell-v0.1.0.apk` と `dist/SHA256SUMS.txt`。版名を変える場合はスクリプトの出力名も更新する。

インストール前にはAPKの版数、SHA-256、端末の既存APKと署名証明書が一致することを確認する。登録鍵との照合は端末側との照合の代わりにはならない。

## 4. Pixel 10aへのデータ保持更新

PATH上の別ADBと混在させず、Android SDKのADBを一貫して使う。複数端末やoffline emulatorが見える場合も、毎回`-s`でPixel 10aを指定する。

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
$deviceSerial = Read-Host 'Pixel 10a serial'

$accelerometerRotationBefore = (& $adb -s $deviceSerial shell settings get system accelerometer_rotation).Trim()
$userRotationBefore = (& $adb -s $deviceSerial shell settings get system user_rotation).Trim()
$releaseApk = Join-Path $publicRepo "dist\chikabell-v$releaseVersion.apk"

& $adb -s $deviceSerial shell dumpsys package com.chikabell.app |
    Select-String 'versionCode=|versionName=|firstInstallTime=|lastUpdateTime='

& $adb -s $deviceSerial install -r $releaseApk
```

`Success`の後に検証集計が失敗しても、すぐ再インストールしない。端末から現在状態を読み直す。

```powershell
& $adb -s $deviceSerial shell dumpsys package com.chikabell.app |
    Select-String 'versionCode=|versionName=|firstInstallTime=|lastUpdateTime='

.\tools\launch_chikabell_preserve_rotation.ps1 -DeviceSerial $deviceSerial

$appProcessId = (& $adb -s $deviceSerial shell pidof com.chikabell.app).Trim()
$accelerometerRotationAfter = (& $adb -s $deviceSerial shell settings get system accelerometer_rotation).Trim()
$userRotationAfter = (& $adb -s $deviceSerial shell settings get system user_rotation).Trim()
```

PowerShellの変数名は大文字・小文字を区別しない。`$pid`は読み取り専用の自動変数`$PID`と同じ扱いになるため、アプリのPIDには必ず`$appProcessId`を使う。

合格条件:

- 予定した`versionCode`と`versionName`になっている。
- `firstInstallTime`が前版から変わっていない。
- 保存済み地点など、更新前のアプリ状態が保持されている。
- `accelerometer_rotation`と`user_rotation`が更新前後で一致する。
- ChikaBellが起動し、fatal crashがない。

## 5. ステージ・秘密情報検査・commit

ステージ対象は明示する。開発用・公開用のどちらでも`git add -A`を使わない。

```powershell
$releaseFiles = @(
    'app/build.gradle.kts'
    'README.md'
    'tools/build_release.ps1'
    # ここへ今回の機能ファイルとテストを明示する
)
git add -- $releaseFiles
git diff --cached --stat
git diff --cached --check

& 'C:\Users\akiso\.codex\scripts\check_secrets.ps1' `
    -RepoRoot $publicRepo `
    -StagedOnly
```

秘密情報検査の警告は件数だけで判断せず、各行を確認する。環境変数名やKotlinの`key =`を検出しただけの誤検知と、実値・トークン・秘密鍵を区別する。実値、private key block、個人データ、端末snapshotがあれば公開を止める。

```powershell
$commitMessage = (Read-Host 'Commit message').Trim()
git commit -m $commitMessage
git status -sb
```

## 6. GitHub PR・CI・main統合

```powershell
git push -u origin HEAD
gh pr create --repo kapioka/chikabell --fill
gh pr checks --repo kapioka/chikabell --watch
```

CIが実行中なら、同じcommitへ不要な再pushやローカル再ビルドを重ねず待つ。CI合格後にPRの差分と対象commitを確認してmainへ統合する。

```powershell
gh pr merge --repo kapioka/chikabell --merge --delete-branch
git switch main
git pull --ff-only
```

## 7. tagとGitHub Release

mainの先頭がPRの統合commitであることを確認してから、注釈付きtagを作る。

```powershell
git log -3 --oneline --decorate
$releaseTag = "v$releaseVersion"
git tag -a $releaseTag -m "ChikaBell $releaseTag"
git push origin $releaseTag

$releaseSha = (Get-FileHash $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
$releaseNotes = @"
（今回の変更を1〜2文で記入）

## 変更内容

- （利用者に分かる変更点を記入）

## 検証

- JVM unit tests、lint、GitHub Actionsの結果を記入
- 前版と同じ署名証明書であることを確認
- Pixel 10aへのデータ保持更新結果を記入

## 対応環境と既知の制限

- Android 10（API 29）以上
- 位置情報や通知は端末、Play services、省電力設定等の影響を受け、即時性や確実性を保証しない

SHA-256: $releaseSha
"@

gh release create $releaseTag `
    $releaseApk `
    (Join-Path $publicRepo 'dist\SHA256SUMS.txt') `
    --repo kapioka/chikabell `
    --title "ChikaBell $releaseTag" `
    --notes $releaseNotes
```

release assetへkeystore、JSON/CSV出力、端末ログ、UI dump、snapshotを含めない。

## 8. 公開物の再取得と最終照合

既存の一時フォルダを再帰削除して空にしない。毎回新しい一時フォルダを作る。

```powershell
$releaseRunId = [guid]::NewGuid().ToString('N')
$downloadDir = Join-Path $env:TEMP "chikabell-release-$releaseRunId"
New-Item -ItemType Directory -Path $downloadDir | Out-Null

gh release download $releaseTag `
    --repo kapioka/chikabell `
    --dir $downloadDir

Get-FileHash $releaseApk -Algorithm SHA256
Get-FileHash (Join-Path $downloadDir "chikabell-v$releaseVersion.apk") -Algorithm SHA256
Get-Content -LiteralPath (Join-Path $downloadDir 'SHA256SUMS.txt')
```

最終合格条件:

- GitHubのmainとtagが意図した統合commitを指す。
- Releaseのtag、タイトル、APK名が同じ版である。
- 再取得APKのSHA-256がローカル検証済みAPKと一致する。
- Pixel 10aの版数がReleaseと一致する。
- 公開用ツリーが`main...origin/main`でクリーンである。

## 途中失敗からの再開表

| 症状 | 完了済みとしてよい範囲 | 再開方法 |
|---|---|---|
| 公開用ツリーでSDKが見つからない | ソース反映まで | `ANDROID_HOME`と`ANDROID_SDK_ROOT`を現在プロセスだけへ設定してGradleから再開 |
| 署名入力ウィンドウがすぐ閉じる | debug検証まで | パスワードを保存せず、同じPowerShellでマスク入力してrelease buildだけ再実行 |
| `Cannot overwrite variable PID`等 | `adb install -r`の`Success`まで | 再インストールせず端末状態を再読取し、変数名を`$appProcessId`へ変更 |
| ADBが複数端末を報告する | APK検証まで | Pixel 10aのserialを確認し、すべてのADB呼び出しへ`-s`を付ける |
| CIが長時間実行中 | push・PR作成まで | 同じrunを待つ。失敗理由が出るまで再pushしない |
| 一時フォルダ削除が安全制約で止まる | Release公開まで | 既存フォルダを消さず、GUID付き新規フォルダへ再ダウンロード |
| 公開後のSHA照合が不一致 | GitHub Release公開まで | Release assetを正しいAPKへ差し替える前に、ローカル・tag・assetの由来を再監査して公開完了扱いを止める |

## まだ自動化しない改善候補

将来の専用スクリプトは、次の境界を満たしてから追加する。

- `versionName`と`versionCode`を1回指定し、更新対象3か所へ反映する。
- PIDだけではなく、GUIDのrun ID、プロセス開始時刻、実行ファイル、終了状態を組み合わせて実行を識別する。
- `prepare`、`device-verify`、`publish`を分け、成功済みチェックポイントから再開できる。
- 署名パスワードはマスク入力し、ファイル、履歴、ログ、チェックポイントへ保存しない。
- `publish`は、対象差分、テスト、署名、端末、秘密情報検査の合格後にだけ明示確認を要求する。
- 既存の開発ツリーや公開用ツリーを自動削除、reset、stash、cleanしない。

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

- `chikabell-v<version>.apk`
- `SHA256SUMS.txt`

Play Store用AAB、ストア掲載文、Data Safety申告は対象外。
