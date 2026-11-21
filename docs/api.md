# Orca Bot Remote API v1

Phase 1 は読み取り専用です。ベース URL は `http://<Mac mini の Tailscale IP または MagicDNS 名 mac-mini>:8787` です。IPv6 のアドレスを URL に直接書く場合は角括弧で囲みます。

全エンドポイントで `Authorization: Bearer <token>` を送ります。応答の Content-Type は `application/json; charset=utf-8` です。以下の `?` は JSON の `null` を許す値を表します。時刻は Unix epoch ミリ秒の数値です。

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
- まとめは設定された自動化の直近20件から最新の完了済み定期実行を選びます。`bot` は自動化定義の Bot 名です。Bot 一覧から名前を特定できない場合は定義にある Bot ID を返します。`runAt` は予定時刻（Orca の `scheduledFor`）です。未設定、該当する実行が無い場合、または該当回の本文がまだ無い場合は `{"summary":null}` を返します。

## エラー応答

| HTTP | JSON | 条件 |
| --- | --- | --- |
| 401 | `{"error":"unauthorized"}` | Bearer トークンが無い、または一致しない |
| 404 | `{"error":"not_found","message":"対象が見つかりません。"}` | 未知のパス、存在しないリソース、または形式が不正なパス引数 |
| 405 | `{"error":"bad_request","message":"GET のみ使用できます。"}` | 既知のパスに GET 以外を送った場合。`Allow: GET` が付きます |
| 502 | `{"error":"orca_failed","message":"Orca の読み取りに失敗しました。"}` | Orca の起動失敗、タイムアウト、無効な応答、その他の CLI エラー |

認証はパスとメソッドの判定より先に行います。
