# πブリッジのアイコン試作

2026年11月20日、利用者が丸みのあるSOFT案を選び、アプリのランチャーアイコンに採用しました。
このディレクトリには原本と、比較用に残すSHARP案を置いています。

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

Android用リソースは次のスクリプトでSOFTのSVGから再生成します。
Pythonの標準ライブラリだけを使います。

```sh
python3 design/icon-concepts/apply-soft.py
```

背景と前景を分離し、外側のマスク形状はAndroidのランチャーに任せます。
前景はadaptive iconの拡張領域を考慮して0.8倍に調整し、丸いマスクでもπが欠けないことを実機上の描画試験で確認しています。
Android 13以降のテーマアイコン用に単色レイヤーも用意しています。
