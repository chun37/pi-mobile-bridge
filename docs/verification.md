# 要件と検証の対応

## 判定の範囲

設計の中核である、Pi TUIを残したまま同じセッションをスマホから操作する方式は実装可能です。
Pi 1.5.2の実APIとherdr 0.9.5のprotocol 22を根拠に実装しています。
未確認事項を推測で埋めず、専用の実Pi TUIとADB接続されたNX789Jで確認します。

試験に使うLLM応答は、loopbackの決定的なfixtureで生成します。
これはモデルの回答品質の試験ではありません。
Piの本物のイベント、キュー、ツール、TUI、セッションファイルを通るため、bridgeの双方向通信をモックだけで合格にする試験でもありません。

## 設計の未確認事項

| 項目 | 確認結果と根拠 |
|---|---|
| extensionからの中断 | `ctx.abort()` が存在し、実機から中断して `agent_settled.aborted=true` を受信した |
| スマホ入力のsource | 実TUIのinputフックで `source=extension`、PCの入力で `source=interactive` を記録した |
| PCの入力途中の文字 | `UNSENT_PC_DRAFT` を入力して未送信にし、スマホからの応答完了後もTUIに残ることを実機試験した |
| herdrの引数 | `pane.read` は `pane_id` と `source`、`pane.send_input` は `text` と配列の `keys`。省略keysをnullにしない |
| 公式連携との共存 | 管理版herdr-agent-stateと同時に読み込み、状態報告、確認ダイアログ、reload、新規、再開、分岐を試験した |

herdrの型定義の原本は `evidence/herdr-schema.json`、公式連携の確認用コピーは `evidence/integration-home/` に保存しています。

## 中核機能

| ID | 要件 | 実装と試験 |
|---|---|---|
| C01 | 既存のPi TUIを置き換えない | extensionは同じプロセスで動く。実TUIのPC入力とスマホ入力を同じsessionIdに記録 |
| C02 | ワークスペース別の一覧 | herdr snapshotのworkspaces、tabs、panesを結合。実機一覧の描画試験 |
| C03 | 状態、cwd、名前、モデル、末尾ID | snapshotとbridge infoを結合。sessionName、model、lastEntryId、lastResponseを配信 |
| C04 | 履歴と差分 | `getBranch()` のmessageをentryId付きで正規化。since既知と未知の双方を実機試験 |
| C05 | ストリーミング | message_updateのcontentIndex別の差分を蓄積し、endで確定内容に置換。単体試験と実機受信を検証 |
| C06 | Markdown、思考、ツール | Compose、Markwon、設定で切り替える薄い文字色の思考本文、成功と失敗を色分けするツールカード |
| C07 | PC入力の識別 | inputのsourceと送信記録を照合し、originを履歴にも永続化。実機でPCラベルを試験 |
| C08 | スマホの通常送信 | idle時のsendUserMessage。実機からツールを使って専用ファイルを作成 |
| C09 | steerとfollowUp | 実行中に両方を送り、queued応答と、同じTUIでの順序付き処理を実機試験 |
| C10 | 中断 | ctx.abortを実行し、最終settledを確認。agent_endだけを完了とは扱わない |
| C11 | 画像 | Photo Picker、転送、履歴、サムネイル表示。API転送試験と画面操作試験を分けて実施 |
| C12 | bridgeなしのペイン | 自分で作成した専用シェルのペインをbridge=falseとして取得し、端末入力と読取を実機試験 |
| C13 | 確認ダイアログ | Piの実confirmを開き、blocked、ターミナル表示、Enter応答を試験 |
| C14 | キーバーと端末ポーリング | Enter、Esc、矢印、Ctrl+C、文字入力。画面表示中だけ1秒ポーリング |
| C15 | 通知 | blocked遷移でAndroidの実通知を検証。sessions.changedとagent.stateの二重通知を抑止する回帰試験 |

2026年11月20日の追加要件として、トップ右下の「＋」からワークスペースを選び、新しいタブを作ってPiを起動する機能を実装した。
専用の実機試験で既存セッションを保持したまま、新規タブのbridgeとチャット入力欄まで確認した。

## プロトコルとライフサイクル

| ID | 要件 | 実装と試験 |
|---|---|---|
| P01 | JSONLと未知フィールド | LFだけで分割。U+2028、U+2029、分割されたUTF-8の試験 |
| P02 | 要求IDとエラー応答 | id、result、errorを扱い、禁止メソッドをエラーにする |
| P03 | バージョン交渉 | helloでprotocol 1を交渉。relay、herdr、bridgeのバージョンを返す |
| P04 | Unix socketの発見 | 起動時走査、fsnotifyによる追従、5秒の補助再照合。非socketや応答しない残骸を使わない |
| P05 | 複数セッション | 1本のSSH接続で二つの実Pi TUIを操作する実機試験 |
| P06 | 詳細購読の選別 | session.closeしたセッションの詳細が流れず、別の購読中セッションは流れることを実機試験 |
| P07 | 履歴とイベントの順序 | subscribe後にsnapshotを取り、履歴のseq以下を除外。openの応答を書いてから詳細イベントを流す |
| P08 | 取りこぼしからの回復 | seqの欠落、herdr切断、events_lostでsnapshotと履歴を取り直す。Goの障害注入試験 |
| P09 | 新規、再開、分岐、reload | sessionIdとinstanceIdで変更を検知。new、resume、forkのreason付きresetを実機試験 |
| P10 | TUI限定と後始末 | rpcではsocketを開かない。shutdownとreloadの冪等性、socket削除、接続終了を試験 |
| P11 | 送信中の仮表示 | clientMsgIdで仮表示と実メッセージを照合。重複IDと別内容の衝突を単体試験 |
| P12 | herdrへの接続表示 | 選択中のセッションへ期限付きmobile=connectedを報告。切断後は期限で消える |

## AndroidとSSH

| ID | 要件 | 実装と試験 |
|---|---|---|
| A01 | Ed25519の端末内生成 | 実機生成と公開鍵の再取得、暗号化された保存ファイルを検証 |
| A02 | Keystoreによる秘密鍵保護 | AES-GCMのラップ鍵をAndroid Keystoreに置く。秘密鍵をPCへ転送しない |
| A03 | 公開鍵認証とexec | 実際のsshdに認証し、pibridge relayをexec。relayは切断で終了 |
| A04 | ホスト鍵のpin | 未固定の鍵は明示照合待ち。異なるpinは接続拒否。双方を実機試験 |
| A05 | SSH専用鍵の制限 | forced command、no-port-forwarding、no-pty、no-agent-forwarding、no-X11-forwardingを登録。別コマンドとPTYを実機試験 |
| A06 | Roomとオフライン | 接続時のキャッシュ読込、履歴維持、接続先が到達不能でも既存会話を読む試験。大きいJSONはSQLiteのsubstrで分割して読み、CursorWindow上限を回避 |
| A07 | 自動再接続 | 実SSHを切断し、指数バックオフ後の再接続と差分同期を実機試験 |
| A08 | 前景サービス | Android 14以降はremoteMessaging。dataSyncの6時間制限とは分離して検証 |
| A09 | 複数マシンと設定 | プロファイルごとにhost、port、user、pin、Roomキャッシュを分離。接続先の編集時はpinを破棄 |
| A10 | 生体認証 | 任意で起動時に要求。未登録の端末では設定を有効にしてロックアウトしない |
| A11 | Tailscale経由 | Wi-Fi経由のTailscaleに加え、試験前後のVPN underlayがCELLULARであることを確認して実機E2E試験に成功 |
| A12 | 独自の外向き待受なし | bridgeは0700ディレクトリ内の0600 Unix socketのみ。新しい外部TCPポートは作らない |
| A13 | 許可リスト | relayは任意herdrメソッドを公開しない。server.stopなどの拒否を試験 |

## Androidの前景サービス型

公式資料は [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) と [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) です。
remoteMessagingは端末間のテキストメッセージ転送と、端末を切り替えた際のメッセージ操作の継続を用途としています。
このアプリの接続継続に当てはまるため、Android 14以降で使用します。

dataSyncの6時間制限を避ける目的でtargetSdkを古くする変更はしていません。
targetSdkは35、実機はAndroid API 36です。
1日利用を確認するには短時間の単体試験だけでは足りないため、長時間の実測は機能試験と分けて記録します。

## 証拠の保存先

詳細な結果は `evidence/` に保存します。
このディレクトリはGit対象外で、端末や接続先に関する原本は公開しません。
最終的に確認できた試験数、APKのSHA-256、長時間試験の時間範囲は、作業報告書に記録します。
未実施や失敗した試験は、成功したものへ読み替えません。
