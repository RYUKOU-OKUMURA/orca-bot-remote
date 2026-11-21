# Orca Bot Remote API

ベース URL は Mac mini の Tailscale IP または完全な MagicDNS 名を使います（例 `http://mac-mini.example.ts.net:8787`）。IPv6 のアドレスを URL に直接書く場合は角括弧で囲みます。

全エンドポイントで `Authorization: Bearer <token>` を送ります。APK のダウンロードを除き、応答の Content-Type は `application/json; charset=utf-8` です。以下の `?` は JSON の `null` を許す値を表します。時刻は Unix epoch ミリ秒の数値です。

## v1 読み取り

| メソッドとパス | 応答 |
| --- | --- |
| `GET /api/health` | `{"ok":true}`。Orca CLI を起動せず、サーバーへの接続と認証を確認します |
| `GET /api/bots` | `{"bots":[{"id":string,"name":string,"specialty":string?,"paused":boolean,"needsYou":number,"avatar":string?,"lastActivityAt":number?,"lastSaid":string?}]}` |
| `GET /api/bots/{botName}/chats` | `{"chats":[{"id":string,"title":string?,"home":boolean,"lastActivityAt":number?,"lastAsked":string?,"lastSaid":string?}]}` |
| `GET /api/bots/{botName}/chats/{chatId}/messages?limit=N` | `{"state":string?,"entries":[{"kind":"message","role":string,"text":string} \| {"kind":"tool","name":string,"state":string?}]}` |
| `GET /api/items` | `{"items":[{"bot":string,"number":number,"title":string,"status":string,"chatId":string?,"lastActivityAt":number?}]}` |
| `GET /api/items/{botName}/{number}` | `{"bot":string,"number":number,"title":string,"status":string,"chatId":string?,"question":{"text":string,"options":[string]}?}` |
| `GET /api/summary` | `{"summary":{"bot":string,"runAt":number,"content":string,"chatId":string?}?}` |

- Bot 一覧の `needsYou` は Bot ごとの判断待ち件数です。`avatar` は `~/.orca/bots/*/bot.json` で Bot ID が一致し、`mascot:A1` のように英大文字1文字と数字1桁で指定されたマスコットです。`lastActivityAt` と `lastSaid` は一番新しい開いているチャットの時刻と最後の返答です。該当する値が無い場合や、追加情報を取得できない場合は `null` です。
- `botName` は Bot 一覧にある名前を URL エンコードしたパス区間です。チャット一覧には開いているチャットを `lastActivityAt` の降順で返します。時刻が `null` の行は末尾に並びます。
- `chatId` は UUID 形式です。履歴を取得できるのは指定した Bot のチャットだけで、閉じたチャットも含みます。別の Bot や Bot 外のセッション、および旧パス `/api/chats/{chatId}/messages` は 404 です。履歴は古い行から順に返します。メッセージの `role` は通常 `user` または `assistant` です。`limit` は 1〜100 に丸めます。省略時や整数として読めない場合は 100 です。
- 判断待ち一覧には全 Bot の `needs_you` 項目を新しい順に返します。`number` は正の整数です。質問があれば現在の質問イベントの本文と選択肢ラベルを返します。選択肢はイベント内の出現順です。質問が無ければ `question` は `null` です。
- まとめは `automations runs --limit 20` で取得した、設定された自動化の直近20回の実行のうち、最新の完了済み定期実行を選びます。`bot` は自動化定義の Bot 名です。Bot 一覧から名前を特定できない場合は定義にある Bot ID を返します。`runAt` は予定時刻（Orca の `scheduledFor`）です。未設定、該当する実行が無い場合、または該当回の本文がまだ無い場合は `{"summary":null}` を返します。

## アプリの更新

| メソッドとパス | 応答 |
| --- | --- |
| `GET /api/app` | `200 {"app":{"versionCode":number,"versionName":string,"sha256":string,"size":number}}`。配信ファイルがない場合やメタデータが無効な場合は `200 {"app":null}` |
| `GET /api/app/apk` | APK の生バイト列。`Content-Type: application/vnd.android.package-archive` とバイト数を示す `Content-Length` を返します。APK を開けない場合は404 |

両方とも Bearer 認証が必須で、405 の `Allow` は `GET` です。`versionCode` は正の安全な整数、`versionName` は印字可能な ASCII 1〜64文字です。`sha256` は公開 APK からサーバーが計算した小文字16進数64桁で、`size` は APK のバイト数です。APK のダウンロードには55秒の読み取り期限を適用しません。

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

通常の書き込みは Bearer トークンに加え、`Content-Type: application/json` を指定します。JSON 本文は最大 64 KiB です。添付の例外は後述します。チャットの `text` は前後の空白を除いて 1〜10000 文字です。判断待ちへの回答は空白以外の文字を1文字以上含み、前後の空白を含めて 1〜8000 UTF-16 コード単位です（Orca の制限）。Orca には入力された文字列を渡します。

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

## チャットのコマンドとスキル

以下の `{chat}` は `/api/bots/{botName}/chats/{chatId}` です。チャットは指定した Bot に所属する必要があります。3つの追加エンドポイントも Bearer 認証が必須で、405 の `Allow` には表のメソッドを返します。Orca とのペアリングが未設定・無効な場合は、一覧取得を含めて 503 `write_unavailable` です。

| メソッドとパス | 入力 | 成功応答 |
| --- | --- | --- |
| `GET {chat}/commands` | なし | 200。現在のセッションの `commands`、`skills`、`options` |
| `POST {chat}/command` | `{"name":"compact","argument":"決定事項を残す"}` | `200 {"ok":true}` |
| `POST {chat}/attachments` | 単一ファイルの生バイト列と `X-File-Name` ヘッダー | 201。次のメッセージで使える添付情報 |

一覧の応答例です。配列の内容はセッションごとに変わります。

```json
{
  "commands": [
    {"name":"compact","description":"会話を要約して圧縮","kind":"conversation","argumentHint":"[指示]"},
    {"name":"model","description":"モデルを変更","kind":"option","argumentHint":"<モデル>"},
    {"name":"help","description":"Show help","kind":"text","argumentHint":null}
  ],
  "skills": [
    {"name":"review","description":"変更をレビュー","path":"/skills/review/SKILL.md","argumentHint":null}
  ],
  "options": {
    "model":{"current":"example","choices":[{"value":"example","label":"Example"}]}
  }
}
```

- `clear` は引数を省略するか空文字にします。`compact` は任意で最大10,000文字の指示を取ります。指示を受け付けるセッションでは `argumentHint` に `[指示]` が付きます。
- `model` と `effort` は選択肢の `value` を `argument` に指定します。空白だけの値は使えません。上限は512 UTF-16コード単位です。`options.effort.choices` は対象モデルの推論強度です。候補のないキーは省略され、現在値が不明なときは `current: null` になります。
- `kind: "text"` のセッション報告コマンドは、例として `{"text":"/help"}` を既存のメッセージ送信 API に渡します。`POST {chat}/command` が受け付ける名前は上記4種類です。未知の名前や不正な引数は400、閉じたチャットへの書き込みは409です。
- スキルを使う場合は `POST {chat}/messages` に `skills: [{"name":"review","path":"/skills/review/SKILL.md"}]` を追加します。上限16件、名前は512、パスは4096 UTF-16コード単位までの空でない文字列で、制御文字は使えません。送信時に現在のセッションの一覧を照合し、名前とパスが一致するものだけを受け付けます。パスが `null` のスキルは指定できません。一覧と一致しない場合は400、一覧取得が未対応の場合は502です。どちらもメッセージは送信されません。

## ファイル添付

**既存チャット**では、先に `POST {chat}/attachments` でファイルを1つずつアップロードします。`X-File-Name` は UTF-8 のファイル名を URL エンコードした値です。デコード後の上限は256 UTF-16コード単位です。`Content-Type` は `application/octet-stream` やファイルの MIME タイプを使えます。`Content-Length` がない転送にも対応します。1ファイルは1バイト〜20 MiBです。空ファイル、上限超過、不正なヘッダー、multipart 形式は400です。

```json
{"path":"/Users/example/Library/Application Support/orca/agent-session-attachments/12345678-abcd-1234-abcd-123456789abc/図.png","name":"図.png","kind":"image","byteLength":12345}
```

次に `POST {chat}/messages` の `attachments` 配列に、201応答の `path`、`kind`、任意の `name` を渡します。最大10件です。添付があれば `text` は省略または空文字にでき、成功時は従来どおり202です。`skills` も同時に指定できます。サーバーは同じ Mac の Orca 添付ストアにある確定済みの通常ファイルを照合します。外部パス、親ディレクトリ参照、シンボリックリンクは400です。

画像の判定には拡張子、MIME タイプ、ファイル先頭のバイトを使います（PNG、JPEG、GIF、WebP、HEIC/HEIF、AVIF、BMP）。画像は `image-ref` ブロックにし、一般ファイルは本文中の `@パス` 参照にします。空白を含むパスは `@"パス"` で囲みます。

**新規チャット**では `POST /api/bots/{botName}/chats` に直接添付を含められます。

```json
{"text":"この資料を見て","inlineAttachments":[{"name":"memo.txt","mimeType":"text/plain","contentBase64":"SGVsbG8="}]}
```

`mimeType` は任意です。`contentBase64` は改行・data URL 接頭辞を含まない標準のパディング付き Base64 です。最大5件、デコード後は1件1バイト〜15 MiB、合計20 MiBまでです。Base64 文字列の内容を除く JSON 全体の上限は64 KiBです。添付だけでも作成でき、成功応答は従来どおり `201 {"chatId":"…"}` です。

サーバーは入力を確認してからチャットを開始し、すべての添付をアップロードして最初のメッセージを送ります。チャット開始後にアップロードや送信が失敗すると、空のチャットや確定済みの添付が残ることがあります。この場合は認証エラー（503）と対象消失（404）を除き、502 `unknown_result` を返します。再送前にチャット一覧と履歴を更新してください。コマンドもメッセージも自動再送しません。
