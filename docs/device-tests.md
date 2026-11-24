# 実機試験の再実行

## リンクのタップ

MarkdownLinkUiTestは合成したassistantメッセージを実際のMessageCardへ渡して表示する。
UiDeviceで本文、URLそのもの、表内のリンクをタップし、Espresso Intentsで外部アプリへの要求を捕捉する。
リンク先とACTION_VIEWなどを照合し、本人のブラウザーのタブや外部サイトへのアクセスは使わない。
コード内のURL、自動リンクと明示リンクの重複、余白、長押し、横スクロール、非対応URI、起動拒否も試験する。
長押しでは文字の選択を確認し、クリップボードを読み書きしない。

```bash
adb -s "$SERIAL" shell am instrument -w \\
  -e class com.chun.pibridge.MarkdownLinkUiTest \\
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner
```


## Markdownテーブルの表示

MarkdownTableUiTestは合成したassistantメッセージを実際のMessageCardへ渡して表示する。
SSH接続、Piの作成、モデルへの送信、本人の会話や写真は不要である。
日本語、列の配置、太字とコード、横スクロール、生成途中の更新、空セル、複数表、コードブロック、表示幅と色の更新を確認する。
横スクロールは専用Activity内の表の座標を取得し、UiDeviceの実タッチで試験する。
初期のレイアウト完了も待ち、onSizeChangedから再レイアウトを要求する構成にはしない。
標準のTablePluginを使うMarkwonをTable.parseへ渡すとセルが描画用の置換spanになって空文字になるため、セルには通常のMarkwonを使う。
試験画像はアプリ内のfiles/markdown-table-proof.pngへ保存する。

```bash
adb -s "$SERIAL" shell am instrument -w \\
  -e class com.chun.pibridge.MarkdownTableUiTest \\
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner
```


## 新しいモデル候補の再取得

LiveModelsDeviceTestにはprofileIdとworkspaceIdを明示します。
専用Piを新規作成し、実ChatScreenの`/model glm`検索、再取得、補完、SDKのモデル変更を確認します。
z.aiのGLMが設定済みであることが前提で、トークンの値を表示したり書き換えたりはしません。
同じsessionIdとinstanceIdを保持したまま変更し、通常のuser messageが0件であることを確認します。
モデルへの推論要求は送らず、自分で作ったペインだけを終了します。
PCでの認証追加後の同一registry更新は、model-refresh.test.mjsの一時認証ファイルを使う実SDK試験で再現します。
本人の認証ファイルに試験用キーを追加しません。

```bash
adb -s "$SERIAL" shell am instrument -w \\
  -e class com.chun.pibridge.LiveModelsDeviceTest \\
  -e profileId verification-pc -e workspaceId "$WORKSPACE" \\
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner
```


## 試験環境

通常のユーザー会話へ試験メッセージを送らないため、専用のherdrペインを二つ使います。
初回の環境ではNX789J、Android API 36、Pi 1.5.2、herdr 0.9.5を使用しました。
以下の `SERIAL`、`PANE`、`PANE2` は自分で作成した試験用ペインに置き換えてください。

```bash
export SERIAL=YOUR_ADB_SERIAL
export PANE=TEST_WORKSPACE:TEST_PANE
export PANE2=TEST_WORKSPACE:SECOND_TEST_PANE
cd /path/to/pi-mobile-bridge
```

端末の接続設定はREADMEの手順で済ませます。
実機試験はID `verification-pc` のプロファイルを使います。
アプリから通常の設定画面で作成する場合は、テストコードのprofileIdも合わせてください。
生成した秘密鍵を検証PCへコピーする必要はありません。
`BridgeDeviceTest#setupProfile`には `-e host "$SSH_HOST" -e user "$SSH_USER" -e pin "$SSH_PIN"` を渡します。
`SSH_PIN`は検証PCから取得して照合したSSHホスト鍵のSHA-256 fingerprintです。
複数経路の試験である `EndpointUiTest` と `BridgeDeviceTest#bothRegisteredAddressesUseSameServer` には、`-e lanHost "$LAN_HOST" -e tailscaleHost "$TAILSCALE_HOST"` を渡します。
いずれも自分の検証環境の値を指定し、通常利用中のペインを試験対象にしないでください。

表示だけの回帰試験は `ThinkingUiTest` と `DisplaySettingsUiTest` を指定します。
この2クラスはSSH接続先とfixtureを必要としません。

## ランチャーアイコンの試験

`LauncherIconDeviceTest`は実機のPackageManagerから導入済みアプリのアイコンを取得し、adaptive iconであること、白とミントの図形が描画されることを確認します。
前景をadaptive iconの拡張領域へ描いたうえで、丸いマスクと角丸の正方形のマスクで図形が欠けないことを確認します。
Android 13以降では単色レイヤーの存在も確認します。
導入済みリソースから描いた画像を `files/soft-launcher-installed.png` へ保存します。
利用者の壁紙や他のアプリを撮影する試験ではありません。

## ツール画像の表示と保存の試験

`ImageUiTest`は、履歴のtoolResultと実行完了イベントの画像、折り畳んだツールカードでの表示、プレビュー、拡大率、元データの保存要求を確認します。
不正なデータ、MIME不一致、6MiBを超えるbase64文字列は拒否します。

`ImageDownloadDeviceTest`はAndroid標準の保存画面で専用の一意な名前を指定し、返されたcontent URIから読み直したデータが原画像と一致することを確認します。
保存先を固定の物理パスと仮定しません。
試験で作ったファイルだけを同じURIで削除し、利用者の既存ファイルには触れません。
Activityの再作成で保存待ちデータが維持されることと、キャンセル後に保存をやり直せることも確認します。

`ImageHistoryDeviceTest`は `-e profileId <PC> -e pane <所有する会話> -e imagePath <その会話でreadした画像パス>` を必須とします。
指定した既存会話の履歴から実際のread結果を取得し、その内容だけを描画してプレビューと拡大を確認します。
新たなモデル入力は送らず、元の会話やファイルも変更しません。
確認情報は `files/images-live-proof.json`、候補画像だけのプレビューは `files/images-live.png` に保存します。

プレビューはデコード後の画素数を制限し、保存ではサムネイルではなく受け取った元のバイト列を使います。
保存先は利用者が選んだcontent URIに限定し、ファイルのパスやURLを解釈して読み出す機能は追加しません。
アプリが保存先選択中に再ロックしても、Activity側の保存処理はチャットのComposableの破棄に依存しません。
OSによるプロセス終了で保存待ちデータを失った場合は、再度画像を選ぶよう案内します。

## 起動時の接続とキャッシュの試験

`AutoConnectUiTest`は設定スイッチの操作と永続化を確認し、試験後に元の設定へ戻します。
`AutoConnectDeviceTest`は、自動接続が有効で保存済みの信頼済みPCがある新しいアプリプロセスで実行します。
接続ボタンを押さずに接続と一覧取得が終わること、同じService開始要求で接続し直さないこと、手動切断後のActivity再作成で再接続しないことを確認します。
この試験は最後に手動切断するため、引き渡し前にアプリを開き直してください。

`ConnectionSpeedDeviceTest`は `-e profileId <保存済みPC>` を指定し、2回接続して段階別の経過時間を `files/connection-speed.json` に保存します。
会話への入力は行いません。
`ConnectionTrace`はアドレス、鍵、会話本文を記録せず、段階名と経過時間だけを保持します。

`OfflineLazyCacheDeviceTest`は専用IDのキャッシュと到達しないlocalhostポートを使い、一覧だけが最初に読み込まれ、オフラインでも選択時に会話を読めることを確認します。
`CacheDeviceTest`は巨大な会話、日本語、絵文字、空文字、欠落行、一覧だけの読込を確認します。
キャッシュは256KiBのバイト列に分割して読み、結合後にUTF-8として解釈します。
DBを消したりスキーマを変更したりしません。

画面試験は端末がロック中やDozingだとComposeの画面を取得できず、失敗または停止します。
その場合は本人にロック解除を依頼し、生体認証やPINを代行しません。

## セッション一覧の並び替えの試験

`SessionOrderingUiTest`はfixtureデータで、旧ボタンの不在、既存のチャット対象の維持、グループの有無、最近使った順、選択方式と閲覧時刻の保存を確認します。
試験で変更した表示方式は元に戻します。

`SessionOrderingDeviceTest`は `-e profileId <PCのID> -e pane <閲覧する既存Pi>` を指定して実行します。
実際の会話を開いて一覧へ戻り、最終閲覧時刻の記録とグループなしの最近使った順を確認します。
プロンプトの送信や/newの実行はしません。
試験後は元の並び替え方式へ戻し、一覧画面にします。
スクリーンショットは `files/recent-sessions.png`、本文を含まない比較情報は `files/session-order-proof.json` に保存します。

## 接続先のデバッグ情報の試験

AttachmentStripUiTestはサムネイルの左右の並び、右上の×、中央の画像だけの解除、重複した写真の個別解除、最後の解除、追加時の横スクロール、表示失敗時の解除を確認します。
ChatAttachmentDeviceTestは実ChatScreenとContentResolverを使い、試験専用pickerで同じ画像を3回選び、本文保持と単体解除を確認します。
試験画像はアプリ所有のIS_PENDING行として作成し、本人のギャラリーに公開せず、最後にその行だけ削除します。
実際のモデル送信は行わず、既存会話や本人の写真にはアクセスしません。

SessionNotificationDeviceTestは完了、応答待ち、名前なし、メタデータ欠落の4条件で、実際に投稿した専用通知のタイトルと本文を確認します。
通知の許可が既に有効であることを要求し、権限を自動変更しません。
通知作成はBridgeServiceと同じ関数を使い、専用tagとchannelの通知だけを最後に削除します。
ユーザーの既存通知の取消、既存セッションの状態変更、モデルへの入力はしません。

DebugInfoUiTestは通常タップと長押しの分離、バージョン表示、明示的な再取得、旧relay、切断、取得失敗、PC変更、拡張reload、取得中に閉じた後の遅延応答を確認します。
0.4.0では製品版の一致と混在の表示も検証し、外部のPi、Herdr、Nodeなどの番号を一致判定に含めません。
DebugInfoTestはコピー文面の許可リスト、nullや不正値の未取得表示、接続時情報と実行中情報の区別を確認します。
DebugInfoDeviceTestにはprofileIdとworkspaceIdを明示します。
試験自身が新しいPiを作成し、実際のChatScreenのターミナルボタンで長押ししてPi版とNode版を確認します。
通常タップでターミナルへ切り替わることと、モデルへのuser messageが0件のままなことも確認します。
新しい拡張0.3.2を読み込むため、main更新後に新規Piを使います。
通常の会話へreloadやプロンプトは送らず、最後に自分が作ったPiだけを終了します。
途中の所有IDと成功結果はfiles/debug-info-device-proof.jsonに保存します。

## 長押しによるセッション終了の試験

SessionTerminationUiTestは通常タップ、長押し、キャンセル、確定、オフライン、旧relay、対象変更、PC変更、二重送信防止、結果不明時の非再送を確認します。
SessionTerminationDeviceTestは明示的なprofileIdとworkspaceIdで新しいPiを一つ作り、その行でキャンセル後に終了を確定します。
既存paneを引数で受け取ることはなく、自分で作成した対象しか終了しません。
モデルへのプロンプトは送りません。
作成直後の所有IDと終了後の結果をfiles/session-termination-device-proof.jsonに記録します。
失敗時はこのファイルの作成済みIDを読み取り専用で確認してから、テストが作成したpaneだけを片付けます。

scripts/test-session-termination.pyは実Herdrに新しいPiを二つ作り、一方は同じtabに追加したpaneを残し、もう一方は空のtabも消えることを確認します。
PiのPID消失と、既存の履歴ファイルがあればその保持を確認します。
空のPiは最初のメッセージまでsessionFileを作らないことがあるため、ファイルがなかった場合は履歴保持の実機確認とは数えません。
実行にはHERDR_ENV=1、明示的な--workspace、--relay、--allow-create-closeが必要です。
モデルへのプロンプトは送らず、失敗時も作成したpane以外は閉じません。

## 新規作成時の開始ディレクトリの試験

scripts/test-workspace-directory.pyは--before-relay、--after-relay、--workspace、--expected-cwd、--allow-create-closeを必須とします。
旧版と修正版でそれぞれ新しいPiを一つだけ作り、実際のBridge info由来のcwdを比較します。
修正版では作成結果のcwdと実Piのcwdの両方がexpected-cwdと一致することをassertします。
作成前から存在するpaneは所有対象とせず、自分で作ったpaneだけを最後に終了します。
モデルへのプロンプトや既存paneへのcdは送りません。
PCの選択状態は変更せず、試験前後のfocused paneを記録します。

Goのworkspace_directory_testは、ラベルやtabのcwdと基準ディレクトリが異なる場合、多ホスト、Unicode、空白を含むパス、保存形式不一致、欠落、不正JSON、重複ID、相対パス、非ディレクトリ、symlink、サイズ上限、再確認時の元の結果の保持を検証します。

## 新しいタブの作成と表示の試験

`NewSessionUiTest`はfixtureなしで「＋」、明示的な確認、作成先の選択、同じIDでの再確認、未接続時の抑止を試験します。
`CacheDeviceTest`は実際の会話DBを変更せず、インメモリーDBで数MiBのキャッシュとUnicodeの分割読込を確認します。

`NewSessionDeviceTest`は実際に新しいPiタブを1つ作り、起動とbridge接続、チャット表示までを確認します。
モデルへのプロンプトは送りませんが、PCにPiプロセスとタブが作成される点に注意してください。
明示的な `-e profileId <検証PCのID> -e workspaceId <作成先>` が必要です。
通常の会話や既存タブはリセットしません。
作成したtabIdとsessionKeyをアプリ専用領域の `files/new-session-proof.json` に記録します。
終了時にアプリは一覧へ戻りますが、テストが作成したタブ自体は、証拠のIDを確認してから作業者が閉じます。
IDを推測したり、他のタブをまとめて閉じたりしないでください。

## スラッシュコマンドと使用率の試験

`SlashCommandsUiTest`は通信を使わず、入力欄の上の候補、前方一致、タップによる入力補完、モデル引数の補完、長いモデル名と使用率、unknown表示、reload案内を確認する。
実機が消灯してsecure keyguardでロックされている場合は試験を開始せず、利用者へ解除を依頼する。
`am instrument`の終了コードではなく、ログ内の`OK`と失敗の有無を確認する。

`SlashCommandsDeviceTest`は通常会話では実行しない。
次節の専用Piの起動引数へ`-e "$PWD/tests/slash-observer.ts"`を追加し、名前を`--name bridge-slash-verification`とする。
この専用Piでのみ、ローカルモデルへ短いfixture入力を一度送って使用率と履歴を用意する。
実機テストにはPCのprofileIdと専用のfixturePane、その現在のfixtureSessionIdを明示する。
テストはモデルがbridge-fixtureであり、セッション名とIDが一致することを確認してから操作する。
登録コマンドの補完と実行による名前変更、model引数の補完とSDK操作、通常のuser messageが増えないことを確認する。
通常会話や省略時のペインへフォールバックしない。

```bash
adb -s "$SERIAL" shell am instrument -w -r \
  -e class com.chun.pibridge.SlashCommandsUiTest \
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell am instrument -w -r \
  -e class com.chun.pibridge.SlashCommandsDeviceTest \
  -e profileId "$PROFILE_ID" -e fixturePane "$FIXTURE_PANE" \
  -e fixtureSessionId "$FIXTURE_SESSION_ID" \
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner
```

`tests/slash-observer.ts`は試験用であり、通常のPiへ導入しない。
専用のBRIDGE_TEST_ROOTへ引数とセッションIDだけを記録し、固定した試験用のセッション名へ変更する。
試験後は作成した専用fixtureだけを停止する。

## 再読み込み結果の実機試験

`ReloadOutputUiTest`は実通信なしで、元の出力、閉じる操作、ターミナルへの表示切替、確認中と未確認と失敗の区別を確認する。
`ReloadOutputDeviceTest`は専用のbridge-fixtureモデルだけを許可し、通常会話では実行しない。
次節の専用設定をコピーし、quietStartup=false、名前をbridge-reload-verificationとする。
追加のslash-observerは不要で、モデルへのプロンプトもHTTPモデルfixtureサーバーも必要ない。
profileId、fixturePane、fixtureSessionIdを明示し、通常の実行ではBridgeの/reload、nativeInput=trueでは専用端末へ/reloadとEnterを送る。
後者はPC側の入力と同じnativeコマンド経路を確認するための試験である。
どちらもリソースと完了表示、セッションID、user message数の維持を確認する。

```bash
adb -s "$SERIAL" shell am instrument -w -r \
  -e class com.chun.pibridge.ReloadOutputDeviceTest \
  -e profileId "$PROFILE_ID" -e fixturePane "$FIXTURE_PANE" \
  -e fixtureSessionId "$FIXTURE_SESSION_ID" \
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner
```

PC入力経路は上の引数へ`-e nativeInput true`を追加する。
公開する試験コードに通常の会話のペインやセッションIDを埋め込まない。
PCの全体checkには、relayの抽出、未確認と失敗、エディター境界、セッションとbridgeの切替を確認する試験も含む。

## 実行状態の試験

SessionUiReducerTest、SessionPacketReducerTest、ReducerTest、StateTransitionsTestを通常のcheckで実行する。
SessionStateUiTestはSSHやモデルを使わず、状態表示と中断ボタン、送信方式の欄を実際に描画する6件の試験である。

```bash
adb -s "$SERIAL" shell am instrument -w \
  -e class com.chun.pibridge.SessionStateUiTest \
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner \
  > evidence/session-state-ui.log 2>&1
```

SessionStateDeviceTestは、下記の専用Pi起動例で名前をbridge-reactive-verificationにした試験用Piだけを操作する。
モデルはbridge-fixture、Pi拡張はexecutionStateに対応した0.3.0とし、tui-observerも明示的に読み込む。
profileId、fixturePane、fixtureSessionIdは必須で、通常のユーザー会話を指定しない。

```bash
adb -s "$SERIAL" shell am instrument -w \
  -e class com.chun.pibridge.SessionStateDeviceTest \
  -e profileId "$PROFILE_ID" -e fixturePane "$FIXTURE_PANE" \
  -e fixtureSessionId "$FIXTURE_SESSION_ID" \
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner \
  > evidence/session-state-device.log 2>&1
```

この試験は専用Piの確認画面を開閉し、ローカルモデルへ2回だけ入力する。
1回目は中断し、2回目は完了を待つ。
完了後は一覧へ戻り、非同期の購読終了応答後も完了表示を保持することを確認する。
その後、履歴再取得、SSH再接続、nativeの/reloadを実行し、状態と履歴が整合することを確認する。
初期件数はキャッシュ表示ではなく、awaitしたlive snapshotから採取する。
同じ専用Piで繰り返せるが、通常のPiで実行しない。
実行後はログのOKを確認し、作成した専用タブとローカルモデルサーバーだけを終了する。

## fixtureと専用Pi

```bash
mkdir -p evidence/test-agent
chmod 700 evidence
cp tests/models.json tests/settings.json evidence/test-agent/
python3 tests/model_fixture.py > evidence/fixture-server.log 2>&1 &
printf '%s\n' "$!" > evidence/fixture-server.pid
```

fixtureが既に起動している場合は、もう一つ起動しません。
`127.0.0.1:18473` の待受とPIDの実行引数を確認してください。
APIキーの `local-test-only` はfixture用のダミーで、外部サービスの資格情報ではありません。

試験用のherdrペインで、次を実行します。
通常のPiとは設定ディレクトリを分けます。

```bash
env PI_CODING_AGENT_DIR="$PWD/evidence/test-agent" \
    PI_OFFLINE=1 PI_TELEMETRY=0 BRIDGE_TEST_ROOT="$PWD" \
    pi --no-extensions --no-skills --no-prompt-templates --no-themes \
       -e "$PWD/extension/index.ts" \
       -e "$PWD/tests/tui-observer.ts" \
       -e "$HOME/.pi/agent/extensions/herdr-agent-state.ts" \
       --provider bridge-fixture --model bridge-fixture --name bridge-verification
```

二つ目のペインも、別の設定ディレクトリに同じmodels.jsonとsettings.jsonを置いて起動します。
`BRIDGE_TEST_ROOT` は専用の検証ファイルの置場です。
tui-observerは入力source、未送信editor、confirm、resume、forkを調べる試験用拡張で、通常のPiには導入しません。

## APKのインストール

```bash
./scripts/check.sh
adb -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell pm grant com.chun.pibridge android.permission.POST_NOTIFICATIONS
```

ホスト鍵と公開鍵の登録が済んだ後、接続と会話を試験します。

```bash
adb -s "$SERIAL" shell am instrument -w \
  -e class com.chun.pibridge.BridgeDeviceTest#hostKeyPinning \
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner \
  > evidence/device-pinning-recheck.log 2>&1

adb -s "$SERIAL" shell am instrument -w \
  -e class com.chun.pibridge.BridgeDeviceTest#sshRelayAndConversation \
  -e pane "$PANE" \
  com.chun.pibridge.test/androidx.test.runner.AndroidJUnitRunner \
  > evidence/device-conversation-recheck.log 2>&1
```

`am instrument` はテスト失敗時でもプロセスの終了コードが0になることがあります。
ログ末尾の `OK (1 test)` を確認し、FAILURES、INSTRUMENTATION_FAILED、クラッシュがないことを判定してください。

## 追加の試験群

同じ `am instrument` のclassを次の値へ置き換えます。

| class | 内容 |
|---|---|
| `com.chun.pibridge.BridgeUiTest` | 実Compose画面での送信、ツール展開、ターミナル、オフライン、steer/followUp、画像、abort、SSH切断、通知 |
| `com.chun.pibridge.PhotoPickerDeviceTest` | 実Photo Pickerで試験用の緑色画像を選択し、送信と描画を確認 |
| `com.chun.pibridge.LifecycleDeviceTest` | PCの未送信入力、confirm、新規、再開、分岐とreset reason |
| `com.chun.pibridge.MultiplexDeviceTest` | 二つの実Piを一接続で購読し、片方の購読だけを解除 |
| `com.chun.pibridge.BridgeDeviceTest#forcedCommandAndNoPty` | 別コマンドでもrelayに固定され、PTYが拒否されること |

MultiplexDeviceTestには `-e pane2 "$PANE2"` も渡します。
Photo Picker試験は日本語表示のAndroid 10以降を対象にし、自分で生成した時刻の画像だけを選択します。
試験でMediaStoreへ追加したファイルは、そのURIだけをfinallyで削除します。
個人の既存画像を選んだり削除したりしません。

bridgeなしのフォールバックは、Piを起動する前の専用シェルのペインで実行します。
`BridgeDeviceTest#terminalOnlyFallback` に `-e fallbackPane <そのペイン>` を渡します。
既存の作業ペインを指定してはいけません。

## 通信経路の検証

LAN、Wi-Fi上のTailscale、LTE上のTailscaleを別々に記録します。
今回のLTE試験では、Wi-Fiを切る前に終了時のWi-Fi復元をtrapで設定しました。
`dumpsys connectivity` の接続中VPNのTransportsがCELLULAR|VPNへ変わったことを確認してから、会話試験を開始しました。
終了直後にもCELLULAR|VPNを確認し、Wi-Fiを元へ戻しました。

単にWi-Fi無効化コマンドが成功しただけでは、LTEで試験した証拠にはしません。
VPNのログインや生体認証は利用者本人が行います。

## スクリーンショット

UI試験はapp専用領域にsessions-render.png、chat-render.png、tool-render.png、terminal-render.pngを保存します。
画像選択試験はimage-render.pngを保存します。

```bash
adb -s "$SERIAL" exec-out run-as com.chun.pibridge \
  cat files/chat-render.png > evidence/chat-render.png
```

撮影対象は専用の検証会話です。
一覧には他のワークスペース名が写ることがあるため、原本は非公開のevidence配下で扱います。

## 終了と失効

試験用の二つのPiだけを終了します。
fixtureはPIDファイルだけを信じず、実行引数とパスを照合してから終了します。
通常のPi、herdr本体、他のSSH鍵は終了・削除しません。
専用鍵を失効させる場合は、該当する公開鍵の行だけをauthorized_keysから外します。
