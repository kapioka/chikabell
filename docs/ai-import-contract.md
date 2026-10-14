# 地点Import Contract

契約: `schemaVersion: 2`。既存アプリv0.1.2以降のJSONバックアップ形式を再利用する。貼り付け・Android JSON共有は今回の更新で追加する操作であり、v0.1.3の操作対応を意味しない。

- データ構造の正本: [JSON Schema](../schemas/location-import-v2.schema.json)。アプリの `LocationTransferCodec` / `LocationValidator` / `LocationImportPlanner` が最終判定する。
- 検索・出力の手順: [検索Workflow](ai-place-search-workflow.md)。既存CSVは [CSV仕様](ai-import-spec.md)。
- 必須のroot: `schemaVersion`、`locations`。新規地点の `id` は省略する。
- 各地点: `name`、`message`、`latitude`、`longitude`、`radiusMeters`、`cooldownMinutes`、`transitionType: "DWELL"`、`enabled`。`loiteringDelaySeconds` は通常60、`sourceUrl` は座標・実在の根拠となるHTTPS URL、`tags` は任意。
- 通常は最大20候補、通知半径300m、再通知720分。検索半径1/3/5kmと通知半径を混同しない。インポート全体の上限は1000地点・200万文字、有効地点は既存と合わせ100件。
- 保証する基本操作: JSON本文をコピー → アプリ「AIで場所を探す」→「JSONを貼り付け」→既存の取り込み確認→明示承認。AI結果の貼り付け上限は10万文字。
- JSON全体か、外側だけが `json` コードフェンスの入力を使う。説明文からの抽出・不正JSONの修復・未対応versionの推測はしない。
- 複数候補、重複、入力エラーを確認し、キャンセルでは保存しない。取り込み自体は所在地や座標の実在を保証しない。利用者は根拠と地図を確認する。

```json
{"schemaVersion":2,"locations":[{"name":"確認済みの正式名称","message":"保存したい理由","latitude":35.0,"longitude":135.0,"radiusMeters":300,"loiteringDelaySeconds":60,"cooldownMinutes":720,"transitionType":"DWELL","enabled":true,"sourceUrl":"https://example.com/place","tags":[]}]}
```

例の場所・URL・座標は形式説明用の架空値。実地点として登録・出力しない。
