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
| `sessions.list` | なし | `sessions` 配列 |
| `session.open` | `sessionKey`, 任意の`since` | `info`, `entries`, `leafId`, `seq`, `reset` |
| `session.close` | `sessionKey` | `closed: true` |
| `session.send` | `sessionKey`, `text`, 任意の`images`, `deliverAs`, `clientMsgId` | `accepted`, `clientMsgId`, `status` |
| `session.abort` | `sessionKey` | 中断要求の結果 |
| `terminal.read` | `paneId`, 任意の`source` | herdrのpane.read結果 |
| `terminal.input` | `paneId`, `text`, 任意の`keys` | herdrのpane.send_input結果 |
| `terminal.keys` | `paneId`, `keys` | herdrのpane.send_keys結果 |

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

relay内部で使うherdrメソッドはsnapshot、events.subscribe、pane.read、pane.send_keys、pane.send_input、pane.report_metadataです。
pane.report_metadataはmobile接続の表示だけに使い、外部のRPCメソッドには公開しません。
任意のherdrメソッド、server.stop、任意のシェル実行をAndroidへ公開しません。

ターミナル入力は、接続したユーザーのherdr内に存在するペインへだけ送れます。
したがって、専用鍵であってもPCのPiやシェルの操作権限を与える点は変わりません。
端末の画面ロックと鍵の失効手順を併用してください。
