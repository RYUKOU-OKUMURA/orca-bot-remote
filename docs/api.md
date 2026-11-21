# Orca Bot Remote API

ベース URL は `http://<Mac mini の Tailscale IP または MagicDNS 名 mac-mini>:8787` です。IPv6 のアドレスを URL に直接書く場合は角括弧で囲みます。

全エンドポイントで `Authorization: Bearer <token>` を送ります。応答の Content-Type は `application/json; charset=utf-8` です。以下の `?` は JSON の `null` を許す値を表します。時刻は Unix epoch ミリ秒の数値です。

## v1 読み取り

| メソッドとパス | 応答 |
| --- | --- |
| `GET /api/bots` | `{"bots":[{"id":string,"name":string,"specialty":string?,"paused":boolean,"needsYou":number}]}` |
| `GET /api/bots/{botName}/chats` | `{"chats":[{"id":string,"title":string?,"home":boolean,"lastActivityAt":number?,"lastAsked":string?,"lastSaid":string?}]}` |
| `GET /api/bots/{botName}/chats/{chatId}/messages?limit=N` | `{"state":string?,"entries":[{"kind":"message","role":string,"text":string} \| {"kind":"tool","name":string,"state":string?}]}` |
| `GET /api/items` | `{"items":[{"bot":string,"number":number,"title":string,"status":string,"chatId":string?,"lastActivityAt":number?}]}` |
| `GET /api/items/{botName}/{number}` | `{"bot":string,"number":number,"title":string,"status":string,"chatId":string?,"question":{"text":string,"options":[string]}?}` |
| `GET /api/summary` | `{"summary":{"bot":string,"runAt":number,"content":string,"chatId":string?}?}` |

- Bot 一覧の `needsYou` は Bot ごとの判断待ち件数です。
- `botName` は Bot 一覧にある名前を URL エンコードしたパス区間です。チャット一覧には開いているチャットを `lastActivityAt` の降順で返します。時刻が `null` の行は末尾に並びます。
- `chatId` は UUID 形式です。履歴を取得できるのは指定した Bot のチャットだけで、閉じたチャットも含みます。別の Bot や Bot 外のセッション、および旧パス `/api/chats/{chatId}/messages` は 404 です。履歴は古い行から順に返します。メッセージの `role` は通常 `user` または `assistant` です。`limit` は 1〜100 に丸めます。省略時や整数として読めない場合は 100 です。
- 判断待ち一覧には全 Bot の `needs_you` 項目を新しい順に返します。`number` は正の整数です。質問があれば現在の質問イベントの本文と選択肢ラベルを返します。選択肢はイベント内の出現順です。質問が無ければ `question` は `null` です。
- まとめは `automations runs --limit 20` で取得した、設定された自動化の直近20回の実行のうち、最新の完了済み定期実行を選びます。`bot` は自動化定義の Bot 名です。Bot 一覧から名前を特定できない場合は定義にある Bot ID を返します。`runAt` は予定時刻（Orca の `scheduledFor`）です。未設定、該当する実行が無い場合、または該当回の本文がまだ無い場合は `{"summary":null}` を返します。

## エラー応答

| HTTP | JSON | 条件 |
| --- | --- | --- |
| 401 | `{"error":"unauthorized"}` | Bearer トークンが無い、または一致しない |
| 404 | `{"error":"not_found","message":"対象が見つかりません。"}` | 未知のパス、存在しないリソース、または形式が不正なパス引数 |
| 405 | `{"error":"bad_request","message":"…"}` | 既知のパスに許可されていないメソッドを送った場合。`Allow` で使用できるメソッドを返します |
| 502 | `{"error":"orca_failed","message":"Orca の読み取りに失敗しました。"}` | Orca の起動失敗、個別の CLI 呼び出しのタイムアウト、無効な応答、その他の CLI エラー |
| 502 | `{"error":"orca_failed","message":"Orca の読み取りが制限時間内に終わりませんでした。"}` | リクエスト全体の処理が55秒の期限に達した場合。実行中の CLI を停止します |

認証はパスとメソッドの判定より先に行います。

## v2 書き込み

Bearer トークンに加え、`Content-Type: application/json` を指定します。本文は最大 64 KiB です。チャットの `text` は前後の空白を除いて 1〜10000 文字です。判断待ちへの回答は空白以外の文字を1文字以上含み、前後の空白を含めて 1〜8000 UTF-16 コード単位です（Orca の制限）。Orca には入力された文字列を渡します。

| メソッドとパス | 本文 | 成功応答 |
| --- | --- | --- |
| `POST /api/bots/{botName}/chats` | `{"text":string}` | `201 {"chatId":string}`。新規チャットを開いて本人として入力します。Orca が開いている空のチャットを再利用する場合があります |
| `POST /api/bots/{botName}/chats/{chatId}/messages` | `{"text":string}` | `202 {"ok":true}`。指定した Bot のチャットに本人として入力します |
| `POST /api/items/{botName}/{number}/answer` | `{"text":string}` | `200 {"ok":true}`。判断待ちに本人として回答します。選択肢ボタンの場合はラベルをそのまま指定します |

| HTTP | JSON | 条件 |
| --- | --- | --- |
| 400 | `{"error":"bad_request","message":"…"}` | Content-Type、本文のサイズ、JSON、または `text` が無効 |
| 404 | `{"error":"not_found","message":"…"}` | 対象の Bot、チャット、項目が見つからない |
| 409 | `{"error":"conflict","message":"…"}` | チャットが閉じている、または項目が判断待ちではない |
| 502 | `{"error":"orca_failed","message":"…"}` | 接続できない、Orca が拒否したなど、送信に失敗したことが確認できる |
| 502 | `{"error":"unknown_result","message":"送れたか確認できませんでした。会話を更新して確かめてください。"}` | 送信後に応答が来ない、接続が切れたなど、結果を確認できない |
| 503 | `{"error":"write_unavailable","message":"Mac mini の書き込み設定が済んでいません。"}` | ペアリング資格情報が無い、または無効 |

チャット一覧とメッセージのパスは `Allow: GET, POST`、回答のパスは `Allow: POST`、それ以外の既存パスは `Allow: GET` です。Bot、チャット、項目の照合は v1 と同じです。

既存チャットへの送信先は、Orca の `partyLocation` が返す現在のセッションです。

同じ項目への回答はこのサーバー内で順番に処理します。Orca ホストは回答時に判断待ち状態を必須としないため、公式クライアントや Bot の更新との競合では、回答がコメントとして記録される場合があります。

Pi エージェントを使う Bot は書き込みの対象外です。

サーバーとアプリは書き込みを自動で再送しません。`unknown_result` のときはアプリで応答の文言を表示し、会話を更新して結果を確認します。
