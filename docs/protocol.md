# Wire protocol 1

## 共通リリース番号とビルド情報

自作のAndroidアプリ、relay、Bridge拡張はrelease.jsonに定義した共通の0.4.0を使用する。
番号は本人の明示的な指示がある場合だけ更新し、細かい修正で自動増分しない。
Android versionCodeも同様に管理する。
番号の一致はプロトコル互換性と同義ではなく、番号が違うだけで接続を拒否しない。

Bridge infoにbuild.revisionとbuild.modifiedを追加した。
これは拡張ロード時に一度だけ取得するソースGit情報で、デバッグ要求のたびに配置ファイルや最新コミットを読み直すものではない。
固定引数のローカルGit照会はoptional locksを無効にし、タイムアウトと出力上限を設ける。
Gitがない配置や、親の別リポジトリに属する配置では未取得とする。
relayのsession.debugは許可した二つのbuildフィールドだけを転送する。

Androidはビルド時のコミットとソース状態をBuildConfigへ埋め込む。
デバッグ画面ではAndroid、稼働relay、選択中の拡張の版を比べ、一致、不一致、一部未取得、未接続を表示する。
Pi、Herdr、Node、Goの固有バージョンはこの比較に含めない。

## Framing

UTF-8のJSONオブジェクトをLFで区切ります。
U+2028、U+2029は区切りにしません。
1フレームの上限は24MiBです。
送信する文字列内の改行はJSONのエスケープを使います。
未知のフィールドは無視し、未知のメソッドはエラーとして返します。

```json
{"id":"1","method":"hello","params":{"protocol":1}}
{"id":"1","result":{"protocol":1,"relayVersion":"0.1.0"}}
{"event":"resync","reason":"herdr_events_lost"}
```

helloの結果には、herdrVersion、herdrProtocol、bridgeVersions、capabilitiesも含みます。
セッション終了に対応するrelayはcapabilitiesに`sessionTerminate`を返します。
詳細デバッグ情報に対応するrelay 0.2.0は`sessionDebug`も返し、helloにrelayRuntimeを含みます。
SSH stdoutはJSONL専用です。
relayは診断メッセージをstdoutへ混ぜません。

## Androidからrelayへの要求

| method | params | 結果 |
|---|---|---|
| `hello` | `protocol: 1` | バージョンとcapabilities |
| `ping` | なし | `pong: true` |
| `sessions.list` | なし | `sessions` 配列。各行に`workspaceId`と`tabId`、bridge接続済みなら`lastActivityAt`を含む |
| `workspaces.list` | なし | `workspaces` 配列。各要素は`id`と`label` |
| `session.create` | `workspaceId`, UUID形式の`requestId` | `tabId`, `sessionKey`, `started`, 任意の`startupError` |
| `session.open` | `sessionKey`, 任意の`since` | `info`, `entries`, `leafId`, `seq`, `reset` |
| `session.close` | `sessionKey` | `closed: true`（購読終了のみ） |
| `session.terminate` | `sessionKey`, `expectedSessionId`, `expectedInstanceId`, `expectedTabId` | 確認済みのPi paneを終了し`closed: true` |
| `session.commands` | `sessionKey` | `commands`, `models`, `thinkingLevels` |
| `session.debug` | `sessionKey`, `expectedSessionId`, `expectedInstanceId` | 実行中relay、Herdr、対象Piのバージョンと識別情報 |
| `session.reloadOutput` | `sessionKey`, `expectedSessionId` | `sessionId`, `instanceId`, `phase`, `text` |
| `session.send` | `sessionKey`, `text`, 任意の`images`, `deliverAs`, `clientMsgId`, `expectedSessionId` | `accepted`, `clientMsgId`, `status`, コマンドでは`message` |
| `session.abort` | `sessionKey`, 任意の`expectedSessionId` | 中断要求の結果 |
| `terminal.read` | `paneId`, 任意の`source` | herdrのpane.read結果 |
| `terminal.input` | `paneId`, `text`, 任意の`keys` | herdrのpane.send_input結果 |
| `terminal.keys` | `paneId`, `keys` | herdrのpane.send_keys結果 |

`lastActivityAt`は、bridgeのinfoが返すsessionFileの更新時刻をUnix epochミリ秒で表した値です。
ファイルが不明、読取不能、通常ファイル以外の場合は0です。
会話本文を読み直さずstatで取得するため、既存Piのextensionの再読込は不要です。
この値はPCの会話ファイル更新時刻であり、PCでタブを見ただけの時刻ではありません。
reloadなどによるメタデータの永続化でも更新されることがあります。
Androidは同じPCとセッションに対する端末内の最終閲覧時刻との大きい方を並び替えに使います。

`sessionKey` はそのherdrのpaneIdです。
複数ホストでは、AndroidのprofileIdと合わせて識別します。
`since` が現在のbranchに見つからない場合は、`reset=true` で現在のbranchを全件返します。
過去の別branchにあるentryIdを、現在のbranchの差分として扱いません。

sendの画像は `{"type":"image","data":"base64...","mimeType":"image/png"}` の配列です。
textは最大1MiB、画像は最大4枚で、デコード後の各画像は4MiBまでです。
`deliverAs` は `steer` または `followUp` です。
実行中でなければ通常送信です。

同じclientMsgIdと同じ内容を再送した場合は `duplicate=true` を返し、再実行しません。
同じIDで内容が変わる場合は `client_msg_id_conflict` にします。
この抑止記録はPiのプロセス内に置くため、Piの再起動をまたぐ保証ではありません。

`accepted` は入力の受理であり、モデルやツールの処理完了ではありません。
送信後のmessage_startで仮表示と対応づけ、agent_settledで完了を判断します。
輸送途中で切れて結果が不明な要求を、Androidが自動で再送することはありません。

## 実行状態のスナップショット

Pi拡張0.3.0のinfoはcapabilitiesに`executionState`を含めます。
infoとsessions.listの各行は`instanceId`と`execution: {state, seq}`を含みます。
historyとsession.openの`info.execution.seq`は、同じ呼び出し内で取得した履歴のseqと一致します。
stateはidle、working、blocked、done、aborted、errorのいずれかです。
Androidが観測できない状態はunknownとして扱います。

session.eventは発行時点のsessionId、instanceId、executionも持ちます。
実行状態は同じidentity内のseqで比較し、古いスナップショットや重複イベントで巻き戻しません。
新しいidentityでは小さいseqを受け入れます。
実行状態が先に更新されても、会話パケットは会話自身のseqで処理します。
旧拡張にexecutionがない場合も受け入れますが、スナップショットの原子性は保証できません。

expectedSessionId付きのabortは、セッションが変わっていたら副作用なしでsession_changedを返します。
この中断要求がunsupportedの場合は、別のセッションを誤って中断しないよう、無条件のEscape送信へ代替しません。
詳しい状態遷移とキャッシュの扱いはexecution-state.mdを参照してください。

## コマンドとコンテキスト使用量

対応するPi拡張はinfoのcapabilitiesに`slashCommands`と`contextUsage`を含めます。
relayはこれらをsessions.listとsessions.changedにも転送します。
infoのcontextUsageは`tokens`、`contextWindow`、`percent`を持ち、使用量不明の値はnullです。
PiのgetContextUsage()を使い、Androidではpercentを小数1桁で表示します。
モデル名が長い場合にも使用率の表示幅を確保します。

Pi拡張0.3.1はinfoに`thinkingLevel`を追加します。
値はSDKのgetThinkingLevel()で取得し、relayがsessions.listとsessions.changedにも転送します。
Androidのチャット画面は`モデル名 (high)`の形式で表示し、`off`も省略しません。
フィールドが欠落、null、空白、文字列以外の場合は括弧を表示しません。
thinking_level_selectも詳細イベントとして転送し、一覧メタデータは既存の定期取得で更新します。
既存のPiは新拡張を読み込むために本人による/reloadが必要です。

session.commandsはPiのgetCommands()から名前、説明、sourceだけを取得します。
sourceInfoなどにあるローカルパスは候補として転送しません。
利用可能なモデルの候補も認証情報を含まない値だけに限定します。
モデルやスキルの内容を取得するための外部APIは呼びません。

先頭が`/`の送信は、対応するPi拡張でのみ受理します。
古い拡張へスラッシュ文字列をそのまま通常プロンプトとして転送することはありません。
登録済みの拡張コマンド、プロンプト、スキルはSDKのexpandPromptTemplatesを明示的に有効にして送信します。
対応する組み込みコマンドはmodel、thinking、compact、name、session、reloadです。
これらは公式SDKの操作を使い、PCの未送信エディターを上書きしたり、Enterを注入したりしません。
newとその他の未対応コマンドはエラーで拒否します。
実行中のPiと画像付きのコマンド送信も拒否します。

コマンドのstatusはcommandです。
messageは受理またはSDK操作の結果であり、拡張コマンドや圧縮処理の非同期完了までは保証しません。
同じclientMsgIdは非同期操作の前に予約し、重複して実行しません。
この記録は通常のsendと同じプロセス内の最大2048件であり、reloadやプロセス再起動をまたぐ保証ではありません。
reloadは応答を書き込んだ後に内部の登録コマンドpibridge-reloadを通して行います。
結果不明時の自動再送は行いません。
Androidはコマンドを通常入力の仮メッセージにせず、入力欄の上に受理結果を表示します。

expectedSessionIdが指定され、実行先の現在のセッションIDと異なる場合はsession_changedを返します。
Androidは送信時の接続とPCを保持し、別PCへ切り替わっても遅延送信を移し替えません。
候補の取得結果もPC、セッション、選択先、再読込の世代を確認してから反映します。

## 再読み込み結果の観測

選択中のセッションでreason=reloadのsession.resetを受けたAndroidは、session.reloadOutputを読み取り専用で要求します。
relayはPiのinfo、herdrのpane.read、再度Piのinfoの順に取得し、セッションIDとbridgeのinstanceIdが変わっていないことを確認します。
別の会話や別のbridgeへ切り替わった場合は結果を返しません。
pane.readはsource=visible、format=textに固定し、スクロールやキー送信は行いません。
認識したリソース見出しとその本文、Reloaded/Reloading/Reload failedの区画だけを抽出します。
エディター前の水平罫線で走査を止め、入力欄に見出しと同じ文字列があっても取得しません。
端末表示全体を転送するAPIではなく、上限64KiBとします。

phaseはcomplete、failed、loading、unavailableのいずれかです。
完了と失敗はPCの対応する表示文字列を取得した場合だけ返します。
リソースの区画だけが見つかっても完了とはしません。
Androidは最大6回、各要求1.5秒と250ms間隔で観測し、最後まで不明なら未確認と表示します。
この再試行は観測だけであり、reload自体を再送しません。
結果の反映前に接続、PC、セッションID、要求の世代を確認します。
結果を閉じると遅れて届いた応答も反映しません。
結果はメモリ上の表示専用で、通常の会話、モデルの入力、キャッシュへ追加しません。
切断中や別の会話を開いている間の過去の再読み込み結果は収集しません。

## ターミナルボタンの長押しによるデバッグ画面

通常のタップはチャットとターミナルの切り替えを維持し、長押しだけでデバッグ画面を開きます。
画面を開くときと再取得ボタンを押すときだけ読み取り専用のsession.debugを呼び、ポーリングしません。
接続先、選択セッション、拡張世代が変わった場合は画面を閉じ、取得中の旧応答も破棄します。
切断中や旧relayでもアプリ版と接続時のhello情報は表示でき、取得できない値を未取得として扱います。

relayは対象Bridgeのinfo、Herdrのsnapshot、再度infoの順に読み、sessionIdとinstanceIdを照合します。
返信はcollectedAt、relay、herdr、bridge、sessionの許可したフィールドだけで構成します。
relayには稼働中プロセスの版、PID、Go、OS、CPU、ホスト名、起動時刻、ビルドコミット、ビルド時の未コミット変更有無を含めます。
Linuxでは/proc/self/exeのリンクで、稼働中の実行ファイルが置換または削除済みかも示します。
配置されたファイルの版と、接続中プロセスの版を同一視しません。

Pi拡張0.3.2のinfo.runtimeは、ホストSDKがexportするVERSIONと、process.version、process.platform、process.archを返します。
SDK開発依存のpackage.jsonや別プロセスのpi --versionから、対象Piの版を推測しません。
古い拡張はruntimeを返さないため、未取得と表示し、必要なら対象Piの処理終了後に本人が/reloadします。
Bridge画面を開くためだけにreloadする必要はありません。
任意のコマンド、環境変数、認証情報、会話本文、sessionFileをデバッグ返信やコピー文面へ含めません。
画面のコピー操作は明示的に行い、接続先とセッションIDが含まれることを案内します。

## 長押しによるセッションの終了

一覧の行を長押しすると、対象の名前、pane、tabと中断の注意を表示して確認します。
キャンセルや通常のタップでは終了要求を送りません。
確認したPC、sessionKey、sessionId、instanceId、tabIdを固定し、切断中や対象の変更時は終了できません。
旧relayでは確認ボタンを無効にし、PC側の更新と再接続を案内します。

relayは最新のHerdr snapshotでpaneとtabを照合し、Pi拡張のinfoでsessionIdとinstanceIdを再確認した後、指定したpaneへの`pane.close`を一度だけ呼びます。
Herdrのエージェント検出がunknownでも、専用Bridge socketの同一性が一致すれば処理できます。
既にpaneがなければ終了済みとして扱います。
Herdrは最後のpaneを閉じると空のtabを取り除きます。
relayから`tab.close`は呼ばないため、同じtabの他のpaneや、確認中に追加されたpaneを巻き込みません。
実行中の処理と未送信入力は失われますが、保存済みの会話履歴やファイルを削除する処理はありません。

終了の二重タップを抑止し、結果不明時の自動再送は行いません。
エラー後はダイアログを閉じて一覧を確認します。
Herdr 0.9.5のpane.closeにはsession identityを条件にする機能がないため、照合からpane.closeまでの間にPC側で同じpaneのPiが置き換わる競合は原子的には防げません。
PCで対象を切り替える操作と同時に終了しないでください。
従来の`session.close`はチャット購読終了だけであり、一覧への復帰や画面切り替えでPiを終了することはありません。

## 新しいタブとセッションの作成

`session.create`は指定したワークスペースにフォーカスを移さず新しいタブを作り、返されたroot paneだけでPiを起動します。
既存セッションへの`/new`送信や、既存ペインの再利用はしません。
クライアントから任意のコマンド、起動引数、環境変数、起動先paneを渡すことはできません。
Piの起動はサーバーの`agent.start`に固定し、通常のPC側設定を使います。

relay 0.2.1はtab.createのcwdを、選択したワークスペースの基準ディレクトリへ明示します。
ワークスペース名やPCで選択中のpaneのcwdから推測せず、クライアントが送ったcwdも採用しません。
Herdr 0.9.5の公開APIはidentity_cwdを返さないため、接続先のHerdr socketと同じディレクトリのsession.json（保存形式version 3）から、workspace IDに一致するidentity_cwdだけを読みます。
絶対パスで既存のディレクトリであることを検証し、状態ファイルが欠落、不正、未対応、対象不明の場合はtab作成前にエラーにします。
選択中pane、ホーム、同名フォルダーなどへ黙って代替しません。
独自の状態ファイル配置を使うHerdrでは、解決できなければ新規作成を拒否します。

session.createの成功結果と永続化記録には、サーバーが決めたcwdも含みます。
同じrequestIdの再確認は従来どおり元の結果を返し、既存tabの移動や別ディレクトリでの再作成は行いません。
AndroidとPi拡張の変更は不要ですが、既存接続で旧relayが動いていればAndroidから切断して再接続する必要があります。

同じrequestIdの再確認では、端末の切断をまたいでも保存済みの結果を返し、タブや起動を重複させません。
作成前に0700ディレクトリ内の0600ファイルへ要求を排他的に予約します。
予約後の結果が不明な場合は`creation_pending`として停止し、自動再実行しません。
別ワークスペースに同じIDを使うと`creation_request_conflict`になります。
記録は`~/.pi/agent/bridge/creations/`に置きます。記録を削除すると、そのIDの再実行抑止も失われます。

Piの起動が失敗または応答待ちでも、作成済みタブは勝手に閉じません。
`startupError`を返し、Androidはその新しいタブをターミナル表示で確認できるようにします。
Androidの作成要求は60秒、Piの起動待ちは20秒を上限とします。

## relayからAndroidへのイベント

| event | 用途 |
|---|---|
| `sessions.changed` | added、updated、removedによる一覧更新 |
| `agent.state` | herdrのエージェント状態 |
| `session.event` | sessionKey、seq、kind、payloadによる詳細配信 |
| `session.reset` | 新規、再開、分岐、reloadなどによる文脈変更 |
| `resync` | 接続断やevents_lostなどの後にsnapshotを再取得する指示 |

詳細のkindにはmessage_start、message_update、message_end、tool_execution_start、tool_execution_update、tool_execution_end、agent_start、agent_end、agent_settledがあります。
加えて、model_select、session_tree、session_compact、session_start、session_shutdownなどを転送します。

seqはbridgeインスタンス内で増加します。
再読込を区別するinstanceIdもinfoに含めます。
Androidはseqの欠落で履歴を取り直します。
重複seqを描画へ重ねません。

Piのmessage_endフックはセッションファイルへの追記より前に呼ばれるため、bridgeは追記を確認した後にhistory_syncを追加で送ります。
relayは詳細購読を開始してから履歴を取得し、履歴のseq以前のイベントを除外します。
session.openの応答は、対応する詳細イベントより先にstdoutへ書きます。

## relayとextensionの間

Unix socketは `~/.pi/agent/bridge/<pid>.sock` です。
同じJSONL形式でinfo、history、subscribe、send、abortを扱います。
infoにはsessionId、sessionFile、cwd、モデル、streaming、leaf、paneId、tabId、herdrSocket、startReason、instanceIdを含みます。

session_startごとにsocketを作り直し、session_shutdownとreloadで既存接続とsocketを閉じます。
ディレクトリは0700、socketは0600です。
relayはsocket以外のファイルや、現在のherdrSocketと一致しないbridgeを採用しません。

## herdrの許可リスト

relay内部で使うherdrメソッドはsession.snapshot、events.subscribe、pane.read、pane.send_keys、pane.send_input、pane.report_metadataです。
新規セッション作成に限りtab.createとagent.startを使い、起動先は今作成したタブのroot pane、kindはpi、追加引数は空に固定します。
確認済みセッションの終了に限り、identityを照合した対象へのpane.closeを使います。
任意のpane.closeやtab.closeをクライアントへ公開しません。
pane.report_metadataはmobile接続の表示だけに使い、外部のRPCメソッドには公開しません。
任意のherdrメソッド、server.stop、任意のシェル実行をAndroidへ公開しません。

ターミナル入力は、接続したユーザーのherdr内に存在するペインへだけ送れます。
したがって、専用鍵であってもPCのPiやシェルの操作権限を与える点は変わりません。
端末の画面ロックと鍵の失効手順を併用してください。
