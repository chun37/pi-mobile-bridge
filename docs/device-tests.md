# 実機試験の再実行

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
