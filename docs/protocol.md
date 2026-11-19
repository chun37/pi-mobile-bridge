# Wire protocol 1

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
| `session.close` | `sessionKey` | `closed: true` |
| `session.send` | `sessionKey`, `text`, 任意の`images`, `deliverAs`, `clientMsgId` | `accepted`, `clientMsgId`, `status` |
| `session.abort` | `sessionKey` | 中断要求の結果 |
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

## 新しいタブとセッションの作成

`session.create`は指定したワークスペースにフォーカスを移さず新しいタブを作り、返されたroot paneだけでPiを起動します。
既存セッションへの`/new`送信や、既存ペインの再利用はしません。
クライアントから任意のコマンド、起動引数、環境変数、起動先paneを渡すことはできません。
Piの起動はサーバーの`agent.start`に固定し、通常のPC側設定を使います。

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
pane.report_metadataはmobile接続の表示だけに使い、外部のRPCメソッドには公開しません。
任意のherdrメソッド、server.stop、任意のシェル実行をAndroidへ公開しません。

ターミナル入力は、接続したユーザーのherdr内に存在するペインへだけ送れます。
したがって、専用鍵であってもPCのPiやシェルの操作権限を与える点は変わりません。
端末の画面ロックと鍵の失効手順を併用してください。
