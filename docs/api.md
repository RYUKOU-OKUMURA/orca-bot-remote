# Orca Bot Remote API

ベース URL は Mac mini の Tailscale IP または完全な MagicDNS 名を使います（例 `http://mac-mini.example.ts.net:8787`）。IPv6 のアドレスを URL に直接書く場合は角括弧で囲みます。

全エンドポイントで `Authorization: Bearer <token>` を送ります。SSE と APK のダウンロードを除き、応答の Content-Type は `application/json; charset=utf-8` です。以下の `?` は JSON の `null` を許す値を表します。時刻は Unix epoch ミリ秒の数値です。

## v1 読み取り

| メソッドとパス | 応答 |
| --- | --- |
| `GET /api/health` | `{"ok":true}`。Orca CLI を起動せず、サーバーへの接続と認証を確認します |
| `GET /api/board` | `{"updatedAt":number,"bots":[…],"sessions":[…],"tasks":[…],"memoTasks":[…]?}`。秘書ボードの取得時刻、全 Bot、稼働中セッション、アクティブな項目、未完了のタスクリストをまとめて返します |
| `GET /api/bots` | `{"bots":[{"id":string,"name":string,"specialty":string?,"paused":boolean,"needsYou":number,"avatar":string?,"lastActivityAt":number?,"lastSaid":string?,"working":boolean,"activeWork":string?}]}` |
| `GET /api/bots/{botName}/chats` | `{"chats":[{"id":string,"title":string?,"home":boolean,"lastActivityAt":number?,"lastAsked":string?,"lastSaid":string?,"working":boolean}]}` |
| `GET /api/bots/{botName}/chats/{chatId}/messages?limit=N` | `{"state":string?,"entries":[{"kind":"message","role":string,"text":string} \| {"kind":"tool","name":string,"state":string?}],"activeWorkers":[{"title":string,"tool":string?}]}` |
| `GET /api/items` | `{"items":[{"bot":string,"number":number,"title":string,"status":string,"chatId":string?,"lastActivityAt":number?}]}` |
| `GET /api/items/{botName}/{number}` | `{"bot":string,"number":number,"title":string,"status":string,"chatId":string?,"question":{"text":string,"options":[string]}?}` |
| `GET /api/summary` | `{"summary":{"bot":string,"runAt":number,"content":string,"chatId":string?}?}` |

- Bot 一覧の `needsYou` は Bot ごとの判断待ち件数です。`avatar` は `~/.orca/bots/*/bot.json` で Bot ID が一致し、`mascot:A1` のように英大文字1文字と数字1桁で指定されたマスコットです。`lastActivityAt` と `lastSaid` は一番新しい開いているチャットの時刻と最後の返答です。該当する値が無い場合や、追加情報を取得できない場合は `null` です。
- Bot の `working` は、本体チャットか配下ワーカーが作業中なら `true` です。`activeWork` は先頭ワーカーのタイトル（最大80文字）、本体チャットだけが作業中なら「応答を作成中」、どちらもなければ `null` です。チャットの `working` はそのチャット自身の稼働を示します。
- 履歴の `activeWorkers` は、その Bot 配下で作業中のワーカーを返します。タイトルとツール名は空白・改行・制御文字をまとめて半角スペースにし、前後の空白を除いた最大80文字の1行に整えます。表示タイトルの重複を除いて最大10件とし、ツール名が空なら `tool` は `null` です。
- 作業状態は最大3秒間キャッシュします。`worktree ps` の取得は CLI の空き待ちを含めて4秒で打ち切り、取得失敗時は通常の応答に `working: false`、`activeWork: null`、`activeWorkers: []` をそれぞれ返します。
- `botName` は Bot 一覧にある名前を URL エンコードしたパス区間です。チャット一覧には開いているチャットを `lastActivityAt` の降順で返します。時刻が `null` の行は末尾に並びます。
- `chatId` は UUID 形式です。履歴を取得できるのは指定した Bot のチャットだけで、閉じたチャットも含みます。別の Bot や Bot 外のセッション、および旧パス `/api/chats/{chatId}/messages` は 404 です。履歴は古い行から順に返します。メッセージの `role` は通常 `user` または `assistant` です。`limit` は 1〜100 に丸めます。省略時や整数として読めない場合は 100 です。
- 判断待ち一覧には全 Bot の `needs_you` 項目を新しい順に返します。`number` は正の整数です。質問があれば現在の質問イベントの本文と選択肢ラベルを返します。選択肢はイベント内の出現順です。質問が無ければ `question` は `null` です。
- まとめは `automations runs --limit 20` で取得した、設定された自動化の直近20回の実行のうち、最新の完了済み定期実行を選びます。`bot` は自動化定義の Bot 名です。Bot 一覧から名前を特定できない場合は定義にある Bot ID を返します。`runAt` は予定時刻（Orca の `scheduledFor`）です。未設定、該当する実行が無い場合、または該当回の本文がまだ無い場合は `{"summary":null}` を返します。

### 秘書ボード

- `bots` は一覧順に `id, name, avatar, working, activeWork, needsYou, paused` を返します。`needsYou` は表示上限を適用する前の判断待ち件数です。
- `sessions` は稼働中の最大20件で、各行に `bot, avatar, kind, title, tool, chatId` が入ります。`kind` は配下ワーカーの `worker` または本体チャットの `chat` です。ワーカーの `chatId` と不明なツール名は `null` です。`worktree ps` 失敗時は 200 で空の `sessions` と各 Bot の `working: false, activeWork: null` を返します。
- `tasks` の各行は `bot, avatar, number, title, status, chatId, lastActivityAt` を持ちます。`needs_you` → `in_progress` → `planned`、同じ状態なら最終活動の新しい順（時刻不明は末尾）で最大30件です。
- `memoTasks` は後述のタスクリストのうち未完了（`done: false`）の行を `GET /api/tasks` と同じ順で最大30件返します。判断待ち（`tasks`）とは別物です。`tasks.json` を読めない場合は他の項目を通常どおり返し、`memoTasks` だけ `null` にしてサーバーのログに記録します。

## リアルタイム通知

`GET /api/events` は Bearer 認証後に `text/event-stream; charset=utf-8` で接続を維持します。トークンがない場合や不正な場合は 401、認証済みの GET 以外は 405（`Allow: GET`）です。同時接続はサーバー全体で8本までで、上限では `503 {"error":"too_many_connections","message":"リアルタイム通知の接続数が上限に達しています。"}` を返します。

| フレーム | 送信条件とデータ |
| --- | --- |
| `event: ready` | 接続直後に `data: {"at":1795299000000}` を送ります。再接続までの変更履歴は配信しないため、現在値は各読み取り API で取得します |
| `event: change` | 約10秒ごとに判断待ち一覧（`items`）、最新まとめの予定時刻（`summary`）、Bot の返答・作業状態等（`bots`）、アクティブな項目（`board`）、タスクリスト（`memoTasks`。担当 Bot の変更を含む）の変化を調べ、例として `data: {"items":false,"summary":false,"bots":false,"board":true,"memoTasks":false,"at":1795299010000}` を送ります。秘書ボードは `board` または `bots` が `true` のときに再取得します。`memoTasks` が `true` のときは `board` も `true` にし、タスクリスト（`GET /api/tasks`）も再取得します。このサーバー経由のタスクの作成・更新・削除は、次の確認を待たずにすぐ通知します。Bot が `tasks.json` を直接書き換えた場合は次の確認で通知します |
| `: ping` | 接続維持のため約25秒ごとに送るコメントです |

接続中の全端末で監視を共有します。最初の正常な取得は比較の基準とし、取得失敗時はその対象の直前の正常値を保持します。最後の端末が切断すると監視を停止します。

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
| `POST /api/bots/{botName}/chats` | `{"text":string,"agent":string?,"model":string?,"effort":string?}` | `201 {"chatId":string}`。新規チャットを開いて本人として入力します。Orca が開いている空のチャットを再利用する場合があります。`agent`、`model`、`effort` は後述 |
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

## 新規チャットのモデルと推論の強さ

| メソッドとパス | 成功応答 |
| --- | --- |
| `GET /api/bots/{botName}/options[?agent={agentId}]` | `200 {"agents":[{"id":string,"label":string,"isDefault":boolean}],"agent":string?,"defaultAgent":string?,"models":[{"id":string,"label":string,"isDefault":boolean,"efforts":[{"value":string,"label":string}]}],"defaultModel":string?,"defaultEffort":string?}` |

- `agents` は新規チャットで選べるエージェントです。Orca の `bots.agentChoices` のうち、`orca agent installed` でこの Mac に入っているものを、その順で返します。Bot の既定エージェント（`orca bot list` の `agent`、`defaultAgent`）は必ず先頭に入り、`isDefault: true` です。`label` は `orca agent installed` の `name`（無ければ ID）です。`bots.agentChoices` が失敗した場合は既定エージェント1件だけです。
- `agent` は今回のモデル一覧の対象です。省略時は既定エージェントです。指定が空・空白だけ・129文字以上・制御文字を含む、または `agents` に無い場合は 400 `bad_request` です。
- モデルは Orca の `agentSession.modelCatalog({agent, waitForListing: true})` から取ります。既定エージェントで一覧が空・失敗のときは、その Bot の開いている Home チャット（なければ最新のチャット）の `agentSession.options` から取ります。既定以外のエージェントで一覧が無い場合（例: `hermes`）は `models: []` の 200 で、アプリはモデルを選ばずにそのエージェントで作成できます。
- `defaultModel` はチャットの現在のモデル、無ければ `isDefault` のモデル ID です。`defaultEffort` はチャットの現在の推論の強さ、無ければそのモデルの `defaultEffort`、`declaredDefaultEffort` の順で、どれも無ければ `null` です。空の ID・値の行は除き、`label` が空なら ID・値を使います。
- Orca は短い別名（例: `id: "opus"`, `resolvedModel: "claude-opus-5-5"`）も同じ `label` で返すため、`resolvedModel` の正式 ID が一覧にある別名は除きます。`resolvedModel` の無い行は、先に出た同じ `label`・同じ `efforts` の行より ID が短く、既定でも現在のモデルでもないものを別名として除きます。同じ ID の2行目も除きます。現在のモデルが除いた別名なら、正式 ID として返します。そのうえで `claude-<系列>-<版>[-YYYYMMDD]`（旧形式 `claude-<版>-<系列>[-YYYYMMDD]` も含む）の ID は系列ごとに最新版だけを残します。版は数値で比べ、同じ版なら日付なしを最新、次に新しい日付を優先します。現在のモデルと既定のモデルは旧版でも残し、並び順は Orca の順のままです。この規則は `GET {chat}/commands` の `/model` の選択肢にも使います。
- Orca が選択肢を返さない場合（`not_found`、選択肢に対応しないエージェント、その他の Orca エラー）は `models: []`、`defaultModel`・`defaultEffort` は `null` です。アプリは選択欄を出さずに既定のモデルで作成します。ペアリング資格情報が無い・無効な場合だけ 503 `write_unavailable` です。Bot 一覧・チャット一覧の CLI 読み取りに失敗した場合は他の読み取りと同じく 502 です。
- モデルが1件以上ある結果は Bot とエージェントの組ごとに30秒キャッシュします。405 の `Allow` は `GET` です。

`POST /api/bots/{botName}/chats` の `agent`、`model`、`effort` は任意です。省略または `null` は既定のままです。`model` と `effort` は前後の空白を除いて空でない、512 UTF-16 コード単位以内の、制御文字を含まない文字列です。`agent` は同じ条件で128文字以内で、さらにチャットを開始する前に `GET /api/bots/{botName}/options` の `agents` と同じ一覧にあることを確かめます。違反は 400 で、チャットは作られません。

Orca の `bots.session.start` には `botId` と、指定時だけ `agent` を渡します。`model` も `effort` も無ければ、そのまま最初のメッセージを送ります。どちらかがあれば、サーバーはチャットを開始した直後に新しいセッションの `agentSession.options` を読み、`model` はその ID が選択肢にあること、`effort` は対象モデル（`model` 指定時はそのモデル、省略時はセッションの現在のモデル、なければ既定のモデル）の `efforts` にあることを確かめます。その後 `agentSession.setOption` を `model` → `effort` の順に送り、最初のメッセージを送ります。`effort` だけの指定もこの規則で受け付けます。

- 選択肢に無い値は 400 `{"error":"bad_request","message":"モデルまたは推論の強さを選択肢から選び直してください。"}` です。このときメッセージは送りませんが、開始済みの空のチャットが残ります。
- 開始後に選択肢の取得や `setOption` が失敗・拒否された場合は、添付の失敗と同じく 502 `unknown_result` を返します（認証エラー 503 と対象消失 404 を除く）。チャットは作成済みのため自動では再送しません。

## タスクリスト

メインBot・アプリ・ウィジェットで共有するタスクリストです。判断待ち（`/api/items`）とは別に、Mac mini の `~/.orca/bots/bot/memory/tasks.json` に保存します。すべて Bearer 認証が必須です。書き込みの本文は `Content-Type: application/json` の最大 64 KiB の JSON オブジェクトです。

| メソッドとパス | 本文 | 成功応答 |
| --- | --- | --- |
| `GET /api/tasks` | なし | `200 {"tasks":[MemoTask]}` |
| `POST /api/tasks` | `{"title":string,"note":string?,"due":string?,"bot":string?}` | `201 {"task":MemoTask}` |
| `PATCH /api/tasks/{id}` | `{"title"?,"note"?,"due"?,"bot"?,"done"?}`（1つ以上） | `200 {"task":MemoTask}` |
| `DELETE /api/tasks/{id}` | なし | `200 {"ok":true,"id":string}` |

`MemoTask` は `{"id":string,"title":string,"note":string?,"due":"YYYY-MM-DD"?,"bot":string?,"done":boolean,"createdAt":number,"updatedAt":number,"doneAt":number?}` です。

- `title` は改行を含む制御文字を半角スペースに置き換え、前後の空白を除いて 1〜200 文字（コードポイント数）です。`note` は前後の空白を除いて最大 2000 文字で、空なら `null` です。`due` は実在する日付の `YYYY-MM-DD` か `null` です。`bot` は担当 Bot の名前で、改行を含む制御文字を半角スペースに置き換え、前後の空白を除いて最大 100 文字（コードポイント数）、空なら `null` です。Bot が実在するかは確かめません（名前の変更や後から作る Bot にも使えるように）。`done` は真偽値です。未知のフィールドや、作成時の `done` は 400 です。
- `done` を `false` から `true` にすると `doneAt` に現在時刻、`true` から `false` にすると `null` を入れます。更新のたびに `updatedAt` を現在時刻にします。
- 並び順は未完了が先で、期限ありを期限の早い順、その後に期限なしを作成の新しい順に並べます。完了済みは後ろで、`doneAt`（無ければ `updatedAt`）の新しい順です。
- 件数の上限は 500 件で、超える作成は 400 です。存在しない `id` は 404 です。`/api/tasks` の `Allow` は `GET, POST`、`/api/tasks/{id}` は `PATCH, DELETE` です。
- ファイルが無い場合は空のリストとして扱い、最初の書き込みでディレクトリ（`700`）とファイル（`600`）を作ります。書き込みは同じディレクトリの一時ファイルから `rename` で置き換えます。JSON が壊れている場合は上書きせず、`500 {"error":"tasks_failed","message":"タスクリストを読み書きできませんでした。"}` を返します。
- Bot が書いた行は、`id` と `title` が文字列なら表示します。それ以外の項目は読み替え（不正な `due` や、無い・文字列でない `bot` は `null` など）、未知のフィールドは更新時もそのまま残します。`id` か `title` が無い行は表示せず、ファイルには残します。
- 書き込みはこのサーバー内で順番に処理します。Bot が同じ瞬間にファイルを直接書き換えた場合の競合は防げません。

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
