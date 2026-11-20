# πブリッジのアイコン試作

このディレクトリは、Pi Mobile Bridgeのアイコン候補を比較するための試作です。
アプリのランチャーアイコンにはまだ適用していません。

| 候補 | SVG原本 | 1024px PNG |
| --- | --- | --- |
| 丸みのあるπ | `01-soft.svg` | `01-soft.png` |
| 直線的なπ | `02-sharp.svg` | `02-sharp.png` |

比較画像は `comparison.png` です。
角丸、丸いマスク、80px、48px、32pxでの見え方を並べています。
濃紺は `#111B30`、白は `#F7FAFC`、ミントは `#79E2C4` です。
図形は独自のパスで描き、ロゴ自体にフォントを使っていません。
比較画像の見出しにはローカルのNoto Sans CJKとSpace Groteskを使います。
フォントファイルは同梱しません。

## 再生成

システムのPythonにPillow、PyGObject、Rsvgのintrospectionデータが必要です。

```sh
python3 design/icon-concepts/generate.py
```

採用後はAndroidのadaptive icon用に背景と前景を分離し、実際のホーム画面で確認します。
この試作の外側の角丸を、そのままadaptive iconの前景へ重ねる予定ではありません。
